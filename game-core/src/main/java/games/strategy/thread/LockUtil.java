package games.strategy.thread;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;

import com.google.common.annotations.VisibleForTesting;

import lombok.extern.java.Log;

/**
 * Utility class for ensuring that locks are acquired in a consistent order.
 *
 * <p>
 * Simply use this class and call acquireLock(aLock) releaseLock(aLock) instead of lock.lock(), lock.release(). If locks
 * are acquired in an inconsistent order, an error message will be printed.
 * </p>
 *
 * <p>
 * This class is not terribly good for multithreading as it locks globally on all calls, but that is ok, as this code is
 * meant more for when
 * you are considering your ambitious multi-threaded code a mistake, and you are trying to limit the damage.
 * </p>
 */
@Log
@SuppressWarnings("ImmutableEnumChecker") // Enum singleton pattern
public enum LockUtil {
  INSTANCE;

  // the locks the current thread has
  // because locks can be re-entrant, store this as a count
  private final ThreadLocal<Map<Lock, Integer>> locksHeld = ThreadLocal.withInitial(HashMap::new);

  // a map of all the locks ever held when a lock was acquired
  // store weak references to everything so that locks don't linger here forever
  private final Map<Lock, Set<WeakLockRef>> locksHeldWhenAcquired = new WeakHashMap<>();
  private final Object mutex = new Object();

  private final AtomicReference<ErrorReporter> errorReporterRef = new AtomicReference<>(new DefaultErrorReporter());

  /**
   * Acquires {@code lock}.
   *
   * <p>
   * If {@code lock} is not currently held by the current thread, verifies that all other locks acquired prior to
   * {@code lock} by the thread that most-recently held {@code lock} are held by the current thread. If not, a message
   * will be written to the associated error reporter.
   * </p>
   */
  public void acquireLock(final Lock lock) {
    // we already have the lock, increase the count
    if (isLockHeld(lock)) {
      final int current = locksHeld.get().get(lock);
      locksHeld.get().put(lock, current + 1);
    } else { // we don't have it
      synchronized (mutex) {
        // all the locks currently held must be acquired before a lock
        if (!locksHeldWhenAcquired.containsKey(lock)) {
          locksHeldWhenAcquired.put(lock, new HashSet<>());
        }
        for (final Lock l : locksHeld.get().keySet()) {
          locksHeldWhenAcquired.get(lock).add(new WeakLockRef(l));
        }
        // we are lock a, check to see if any lock we hold (b) has ever been acquired before a
        for (final Lock l : locksHeld.get().keySet()) {
          final Set<WeakLockRef> held = locksHeldWhenAcquired.get(l);
          // clear out of date locks
          held.removeIf(weakLockRef -> weakLockRef.get() == null);
          if (held.contains(new WeakLockRef(lock))) {
            errorReporterRef.get().reportError(lock, l);
          }
        }
      }
      locksHeld.get().put(lock, 1);
    }

    lock.lock();
  }

  public void releaseLock(final Lock lock) {
    int count = locksHeld.get().get(lock);
    count--;
    if (count == 0) {
      locksHeld.get().remove(lock);
    } else {
      locksHeld.get().put(lock, count);
    }

    lock.unlock();
  }

  public boolean isLockHeld(final Lock lock) {
    return locksHeld.get().containsKey(lock);
  }

  @VisibleForTesting
  ErrorReporter setErrorReporter(final ErrorReporter errorReporter) {
    return errorReporterRef.getAndSet(errorReporter);
  }

  @VisibleForTesting
  interface ErrorReporter {
    void reportError(Lock from, Lock to);
  }

  private static final class DefaultErrorReporter implements ErrorReporter {
    @Override
    public void reportError(final Lock from, final Lock to) {
      log.severe("Invalid lock ordering at, from:" + from + " to:" + to + " stack trace:" + getStackTrace());
    }

    private static String getStackTrace() {
      final StackTraceElement[] trace = Thread.currentThread().getStackTrace();
      final StringBuilder builder = new StringBuilder();
      for (final StackTraceElement e : trace) {
        builder.append(e.toString());
        builder.append("\n");
      }
      return builder.toString();
    }
  }

  private static final class WeakLockRef extends WeakReference<Lock> {
    // cache the hash code to make sure it doesn't change if our reference has been cleared
    private final int hashCode;

    WeakLockRef(final Lock referent) {
      super(referent);
      hashCode = Objects.hashCode(referent);
    }

    @Override
    public boolean equals(final Object o) {
      if (o == this) {
        return true;
      }
      if (o instanceof WeakLockRef) {
        final WeakLockRef other = (WeakLockRef) o;
        return other.get() == this.get();
      }
      return false;
    }

    @Override
    public int hashCode() {
      return hashCode;
    }
  }
}
