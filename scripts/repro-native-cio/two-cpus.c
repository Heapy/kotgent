#define _GNU_SOURCE
#include <dlfcn.h>
#include <unistd.h>
#include <sys/sysinfo.h>

long sysconf(int name) {
    if (name == _SC_NPROCESSORS_ONLN || name == _SC_NPROCESSORS_CONF) return 2;
    long (*real_sysconf)(int) = dlsym(RTLD_NEXT, "sysconf");
    return real_sysconf(name);
}

int get_nprocs(void) { return 2; }
int get_nprocs_conf(void) { return 2; }
