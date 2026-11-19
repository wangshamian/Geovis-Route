package org.heigit.geovisroute.routing;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 路网更新全局读写锁。
 * <p>
 * 所有读取图的请求（路径规划、等时线、吸附、矩阵、导出、越野/混合路由）持读锁；
 * 运行时加载自定义路网的接口持写锁。写锁持有期间，读请求快速失败返回 503，避免长时间挂起。
 */
public final class GraphUpdateLock {
    private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock(true);

    private GraphUpdateLock() {
    }

    public static boolean tryReadLock(long timeout, TimeUnit unit) throws InterruptedException {
        return LOCK.readLock().tryLock(timeout, unit);
    }

    public static void unlockRead() {
        LOCK.readLock().unlock();
    }

    public static boolean tryWriteLock() {
        return LOCK.writeLock().tryLock();
    }

    public static boolean tryWriteLock(long timeout, TimeUnit unit) throws InterruptedException {
        return LOCK.writeLock().tryLock(timeout, unit);
    }

    public static void unlockWrite() {
        LOCK.writeLock().unlock();
    }
}
