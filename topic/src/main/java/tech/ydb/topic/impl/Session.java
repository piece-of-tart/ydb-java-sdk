package tech.ydb.topic.impl;

/**
 * @author Nikolay Perfilov
 */
public interface Session {
    void startAndInitialize();
    void shutdown();
}
