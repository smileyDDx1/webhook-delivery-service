package com.debangshu.webhooks.common;

/**
 * Also used when the row exists but belongs to another application: the caller gets a 404 either way,
 * so one application can't find out which ids other applications have.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String resource, Object id) {
        super(resource + " " + id + " was not found");
    }
}
