# Native CIO premature HTTP disconnect

This standalone Kotlin/Native Linux project uses only Ktor CIO and coroutines. It imports no Kotgent
code. Each of 1,000 server/client lifecycles sends POST, POST, POST, GET, GET, POST, reading every
response body. The default setup intermittently throws:

```text
io.ktor.utils.io.ClosedReadChannelException:
Failed to parse HTTP response: the server prematurely closed the connection
```

`explicitClose` runs the same sequence with `Connection: close`. It is a comparison for the test-fixture
workaround, not an engine fix. The exact engine defect has not been established.

## Build and run

Use the repository's pinned Kotlin Toolchain wrapper from this directory:

```sh
cd scripts/repro-native-cio
../../kotlin task :repro-native-cio:linkLinuxX64TestDebug
```

Inside a Kotgent session, wrap the command with `kotgent mutex run kotlin-build --`. macOS can
cross-compile this binary; execute it on Linux x86-64. The standalone `project.yaml` excludes all
Kotgent modules and plugins.

Run on a two-CPU Linux machine, or compile the diagnostic preload shim below. A Docker CPU quota or
cpuset alone may leave Kotlin/Native reporting the host's processor count. The test prints the count
observed by Kotlin/Native; the shim changes it only for the test process.

```sh
gcc -shared -fPIC -o two-cpus.so two-cpus.c -ldl
repro_bin=build/tasks/_repro-native-cio_linkLinuxX64TestDebug/repro-native-cio_test.kexe
LD_PRELOAD="$PWD/two-cpus.so" "$repro_bin" --ktest_filter=CioReproTest.defaultConnections --ktest_repeat=10
LD_PRELOAD="$PWD/two-cpus.so" "$repro_bin" --ktest_filter=CioReproTest.explicitClose --ktest_repeat=10
```

Run the two variants serially. There are no retries inside the test: `--ktest_repeat` reports each
failure and returns a failing exit status. To compare versions, change both Ktor coordinates in
`module.yaml` together, rebuild, and repeat the same commands.

## Observed comparison

On 2026-10-04, with Ubuntu 22.04 x86-64, Kotlin 2.4.20, coroutines 1.11.0, and a reported processor
count of two:

| Ktor | Variant | Failed test repetitions / total |
| --- | --- | --- |
| 3.5.1 | Default connections | 1 / 10 |
| 3.6.0 | Default connections | 2 / 10 |
| 3.6.0 | Explicit `Connection: close` | 0 / 10 |

All failures above were the premature-response EOF, not assertion mismatches or timeouts. The close
variant completed 10,000 server/client lifecycles and 60,000 requests. An earlier run of the same
HTTP sequence on 3.6.0 failed 5 / 10 repetitions; these are observations of an intermittent failure,
not estimates of a stable failure rate. Failure on 3.5.1 means this evidence does not establish a
regression introduced by 3.6.0.
