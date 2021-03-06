/* 
 * Copyright chinesejie    
 *    
 */
package com.demo.lock.redis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;

import org.apache.log4j.Logger;

/**
 * redis 锁的工具类
 *
 * @author chinesejie
 */
public class RedisLockUtil {
    private static final Logger logger = Logger.getLogger(RedisLockUtil.class);
    private static Object schemeLock = new Object();
    private static Map<String, RedisLockUtil> instances = new ConcurrentHashMap();

    public static RedisLockUtil getInstance(String schema) {
        RedisLockUtil u = instances.get(schema);
        if (u == null) {
            synchronized(schemeLock) {
                u = instances.get(schema);
                if (u == null) {
                    LockObserver lo = new LockObserver(schema);
                    u = new RedisLockUtil(schema, lo);
                    instances.put(schema, u);
                }
            }
        }
        return u;
    }

    private Object mutexLock = new Object();
    private Map<String, Object> mutexLockMap = new ConcurrentHashMap();
    private Map<String, RedisReentrantLock> cache = new ConcurrentHashMap<String, RedisReentrantLock>();
    private DelayQueue<RedisReentrantLock> dq = new DelayQueue<RedisReentrantLock>();
    private AbstractLockObserver lo;

    // private 只给内部用
    private RedisLockUtil(String schema, AbstractLockObserver lo) {
        Thread th = new Thread(lo);
        th.setDaemon(false);
        th.setName("Lock Observer:" + schema);
        th.start();
        clearUselessLocks(schema);
        this.lo = lo;
    }

    public void clearUselessLocks(String schema) {
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!SystemExitListener.isOver()) {
                    try {
                        RedisReentrantLock t = dq.take(); // dq是可重入锁的集合
                        if (t.clear()) {
                            String key = t.getKey();
                            synchronized(getMutex(key)) {
                                cache.remove(key);
                            }
                        }
                        t.resetCleartime();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }

        });
        th.setDaemon(true); // deamon模式,只要用户线程还有,他就不会退出. 很多时候 清理线程就是deamon
        th.setName("Lock cleaner:" + schema);
        th.start();
    }

    private Object getMutex(String key) {
        Object mx = mutexLockMap.get(key);
        if (mx == null) {
            synchronized(mutexLock) {
                mx = mutexLockMap.get(key);
                if (mx == null) {
                    mx = new Object();
                    mutexLockMap.put(key, mx);
                }
            }
        }
        return mx;
    }

    private RedisReentrantLock getLock(String key, boolean addref) {
        RedisReentrantLock lock = cache.get(key);
        if (lock == null) {
            synchronized(getMutex(key)) {
                lock = cache.get(key);
                if (lock == null) {
                    lock = new RedisReentrantLock(key, lo);
                    cache.put(key, lock);
                }
            }
        }
        if (addref) {
            if (!lock.incRef()) {
                synchronized(getMutex(key)) {
                    lock = cache.get(key);
                    if (!lock.incRef()) {
                        lock = new RedisReentrantLock(key, lo);
                        cache.put(key, lock);
                    }
                }
            }
        }
        return lock;
    }

    public void reset() {
        for (String s : cache.keySet()) {
            getLock(s, false).unlock();
        }
    }

    /**
     * 尝试加锁
     * 如果当前线程已经拥有该锁的话,直接返回,表示不用再次加锁,此时不应该再调用unlock进行解锁
     *
     * @param key
     *
     * @return
     *
     * @throws Exception
     * @throws InterruptedException
     */
    public LockStat lock(String key) {
        return lock(key, -1);
    }

    public LockStat lock(String key, int timeout) {
        RedisReentrantLock ll = getLock(key, true);
        ll.incRef();
        try {
            if (ll.isOwner(false)) {
                ll.descrRef();
                return LockStat.NONEED;
            }
            if (ll.lock(timeout)) {
                return LockStat.SUCCESS;
            } else {
                ll.descrRef();
                if (ll.setCleartime()) {
                    dq.put(ll);
                }
                return null;
            }
        } catch (LockNotExistsException e) {
            ll.descrRef();
            return lock(key, timeout);
        } catch (RuntimeException e) {
            ll.descrRef();
            throw e;
        }
    }

    public void unlock(String key, LockStat stat) {
        unlock(key, stat, false);
    }

    public void unlock(String key, LockStat stat, boolean keepalive) {
        if (stat == null) {
            return;
        }
        if (LockStat.SUCCESS.equals(stat)) {
            RedisReentrantLock lock = getLock(key, false);
            boolean candestroy = lock.unlock();
            if (candestroy && !keepalive) {
                if (lock.setCleartime()) {
                    dq.put(lock);
                }
            }
        }
    }

    public static enum LockStat {
        NONEED,
        SUCCESS
    }
}
