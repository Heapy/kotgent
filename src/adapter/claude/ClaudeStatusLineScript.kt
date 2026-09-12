package io.kotgent.adapter.claude

import io.kotgent.core.USAGE_HEARTBEAT_MILLIS
import io.kotgent.core.USAGE_RETENTION_MILLIS

/** macOS ships the Perl core modules used here; no jq or user-installed runtime is needed. */
internal object ClaudeStatusLineScript {
    fun command(
        port: Int,
        headerFilePath: String,
        operatorStatusLineCommand: String? = null,
        stateDirectory: String = stateDirectory(headerFilePath),
        curlPath: String = "/usr/bin/curl",
        renderMicros: Long? = null,
        renderTicks: Long? = null,
        bootId: String? = null,
    ): String = listOf(
        "/usr/bin/perl", "-e", program,
        "http://127.0.0.1:$port${ClaudeHookConfig.USAGE_INGRESS_PATH}",
        headerFilePath, stateDirectory, operatorStatusLineCommand.orEmpty(), curlPath,
        renderMicros?.toString().orEmpty(),
        renderTicks?.toString().orEmpty(), bootId.orEmpty(),
    ).joinToString(" ", transform = ::quote)

    private fun stateDirectory(headerFilePath: String): String {
        val slash = headerFilePath.lastIndexOf('/')
        val parent = if (slash < 0) "." else headerFilePath.substring(0, slash)
        return "$parent/claude-usage"
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private val program = $$"""
        use strict;
        use warnings;
        use JSON::PP;
        use Digest::SHA qw(sha256_hex);
        use Fcntl qw(:flock);
        use Time::HiRes qw(time clock_gettime CLOCK_MONOTONIC);
        use File::Temp qw(tempfile);
        use File::Path qw(make_path);
        use POSIX qw(_exit);

        my ($url, $header, $directory, $operator, $curl, $test_micros, $test_ticks, $test_boot) = @ARGV;
        binmode STDIN;
        binmode STDOUT;
        my $render_micros = length($test_micros) ? 0 + $test_micros : int(time() * 1000000);
        my $render_ticks = length($test_ticks) ? 0 + $test_ticks : int(clock_gettime(CLOCK_MONOTONIC) * 1000000);
        sub execute_operator {
            exec '/bin/sh', '-c', $_[0];
            exit 127;
        }
        my ($raw, $child);
        if (length($operator)) {
            # Establish replay before consuming stdin; disk failures belong only to capture.
            pipe(my $reader, my $writer) or execute_operator($operator);
            my $relay = fork();
            if (!defined($relay)) {
                close $reader;
                close $writer;
                execute_operator($operator);
            }
            if ($relay != 0) {
                close $writer;
                open STDIN, '<&', $reader or execute_operator($operator);
                close $reader;
                execute_operator($operator);
            }
            close $reader;
            open STDOUT, '>', '/dev/null' or _exit(0);
            open STDERR, '>', '/dev/null' or _exit(0);
            $raw = do { local $/; <STDIN> };
            $raw = '' unless defined $raw;
            binmode $writer;
            {
                local $SIG{PIPE} = 'IGNORE';
                print {$writer} $raw;
                close $writer;
            }
            $child = 0;
        } else {
            $raw = do { local $/; <STDIN> };
            $raw = '' unless defined $raw;
            $child = fork();
        }
        if (defined($child) && $child == 0) {
            open STDIN, '<', '/dev/null' or _exit(0);
            open STDOUT, '>', '/dev/null' or _exit(0);
            open STDERR, '>', '/dev/null' or _exit(0);
            my @temporary_paths;
            eval {
                local $SIG{ALRM} = sub { die "capture preparation timed out\n" };
                alarm 2;
                my $json = JSON::PP->new->canonical->utf8;
                my $payload = $json->decode($raw);
                die "missing quota\n" unless ref($payload) eq 'HASH' && ref($payload->{rate_limits}) eq 'HASH';
                my $session = $payload->{session_id};
                die "invalid session\n" unless defined($session) && !ref($session) && $session =~ /\A[A-Za-z0-9._-]{1,256}\z/;
                my $hash = sha256_hex($json->encode($payload->{rate_limits}));
                my $boot = $test_boot;
                if (!length($boot)) {
                    open my $boot_file, '-|', '/usr/sbin/sysctl', '-n', 'kern.bootsessionuuid' or die "read boot identity\n";
                    $boot = <$boot_file>;
                    close $boot_file or die "read boot identity\n";
                    chomp $boot if defined $boot;
                }
                die "invalid boot identity\n" unless defined($boot) && $boot =~ /\A[A-Za-z0-9._-]{1,128}\z/;
                umask 0077;
                make_path($directory, { mode => 0700 }) unless -d $directory;
                my $key = sha256_hex($boot . "\0" . $session . "\0" . ($ENV{TMUX} // '') . "\0" . ($ENV{TMUX_PANE} // ''));
                my $state_path = "$directory/$key.json";
                open my $lock, '>>', "$directory/.capture.lock" or die "open capture lock\n";
                flock($lock, LOCK_EX) or die "lock capture state\n";
                # One permanent lock keeps pruning from unlinking a lock another writer has opened.
                my $cleanup_time = time();
                my $last_cleanup = (stat("$directory/.cleanup"))[9];
                if (!defined($last_cleanup) || $cleanup_time < $last_cleanup || $cleanup_time - $last_cleanup >= 86400) {
                    opendir my $entries, $directory or die "list capture state\n";
                    while (my $name = readdir $entries) {
                        my $retention = $name =~ /\A[0-9a-f]{64}\.json\z/ ? $${USAGE_RETENTION_MILLIS / 1_000} :
                            $name =~ /\A\.(?:state|body)-[A-Za-z0-9_]+\z/ ? 86400 : undef;
                        next unless defined $retention;
                        my $path = "$directory/$name";
                        my $modified = (stat($path))[9];
                        unlink $path if defined($modified) && $cleanup_time - $modified >= $retention;
                    }
                    closedir $entries;
                    open my $cleaned, '>', "$directory/.cleanup" or die "mark capture cleanup\n";
                    close $cleaned;
                }
                my $state;
                if (open my $stored, '<', $state_path) {
                    local $/;
                    $state = eval { $json->decode(<$stored>) };
                    close $stored;
                }
                my $valid = ref($state) eq 'HASH' &&
                    defined($state->{incarnation}) && $state->{incarnation} =~ /\A[0-9a-f]{32}\z/ &&
                    defined($state->{hash}) && $state->{hash} =~ /\A[0-9a-f]{64}\z/;
                for my $field (qw(revision captured_at last_sent render_ticks)) {
                    $valid = 0 unless $valid && defined($state->{$field}) && !ref($state->{$field}) && $state->{$field} =~ /\A[0-9]+\z/;
                }
                if ($valid) {
                    die "superseded render\n" if $render_ticks < $state->{render_ticks};
                    die "ambiguous render\n" if $render_ticks == $state->{render_ticks} && $hash ne $state->{hash};
                } else {
                    open my $random, '<', '/dev/urandom' or die "open random source\n";
                    my $bytes;
                    die "read random source\n" unless read($random, $bytes, 16) == 16;
                    close $random;
                    $state = { incarnation => unpack('H*', $bytes), revision => 0, last_sent => 0 };
                }
                my $millis = int($render_micros / 1000);
                my $ticks = int($render_ticks / 1000);
                my $changed = !$valid || $hash ne $state->{hash};
                if ($changed) {
                    $state->{revision}++;
                    $state->{captured_at} = $millis;
                    $state->{hash} = $hash;
                }
                my $send = $changed || $ticks - $state->{last_sent} >= $${USAGE_HEARTBEAT_MILLIS};
                $state->{last_sent} = $ticks if $send;
                $state->{render_ticks} = $render_ticks;
                my ($saved, $temporary_state) = tempfile('.state-XXXXXX', DIR => $directory, UNLINK => 1);
                push @temporary_paths, $temporary_state;
                print {$saved} $json->encode($state) or die "write capture state\n";
                close $saved or die "close capture state\n";
                rename $temporary_state, $state_path or die "replace capture state\n";
                close $lock;
                if ($send) {
                    my $envelope = $json->encode({
                        source => {
                            id => "$session:$state->{incarnation}",
                            revision => 0 + $state->{revision},
                            capturedAt => 0 + $state->{captured_at},
                        },
                        payload => $payload,
                    });
                    my ($body, $body_path) = tempfile('.body-XXXXXX', DIR => $directory, UNLINK => 1);
                    push @temporary_paths, $body_path;
                    unlink $body_path;
                    binmode $body;
                    print {$body} $envelope or die "write usage body\n";
                    seek($body, 0, 0) or die "rewind usage body\n";
                    open STDIN, '<&', $body or die "restore usage body\n";
                    close $body;
                    alarm 0;
                    exec $curl, '-sS', '-o', '/dev/null', '--connect-timeout', '2', '--max-time', '5',
                        '-X', 'POST', $url, '-H', '@' . $header, '-H', 'Content-Type: application/json',
                        '--data-binary', '@-';
                    die "start curl\n";
                }
            };
            alarm 0;
            unlink @temporary_paths if @temporary_paths;
            _exit(0);
        }
        exit 0;
    """.trimIndent()
}
