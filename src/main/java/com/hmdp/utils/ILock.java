package com.hmdp.utils;

/**
 * @author liuyichen
 * @version 1.0
 */
public interface ILock {
    public boolean tryLock(Long timeoutSec);
    public void unlock();
}
