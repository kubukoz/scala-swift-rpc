// iOS-simulator link shim for Scala Native's javalib.
//
// Scala Native's `java.lang.impl.PosixThread` calls `pthread_condattr_setclock`
// to make condition variables use CLOCK_MONOTONIC. On Linux that's a standard
// glibc extension; macOS happens to export the symbol from libSystem (though
// its header hides it), so the desktop FFI build links by luck. The iOS
// Simulator SDK does NOT export it, so the force-loaded Scala Native archive
// fails to link with an undefined `_pthread_condattr_setclock`.
//
// This is a no-op stub returning success. The only effect of not honoring the
// requested clock is that affected condvars fall back to the default
// (realtime) clock — harmless for a UI app; the SSR runtime doesn't depend on
// monotonic-clock condvar timeouts. Defined weak so that if a future SDK ever
// provides the real symbol, that one wins.
//
// The proper long-term fix belongs upstream in Scala Native (guard the call
// behind an Apple linktime check, as `time.c` already does for other Apple
// divergences); this shim keeps the iOS host self-contained until then.

#include <pthread.h>

__attribute__((weak)) int pthread_condattr_setclock(pthread_condattr_t *attr,
                                                    clockid_t clock_id) {
  (void)attr;
  (void)clock_id;
  return 0;
}
