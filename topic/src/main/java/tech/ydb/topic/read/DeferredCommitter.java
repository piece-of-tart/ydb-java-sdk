package tech.ydb.topic.read;

import tech.ydb.topic.read.events.DataReceivedEvent;
import tech.ydb.topic.read.impl.DeferredCommitterImpl;

/**
 * A helper class that is used to call deferred commits.
 * Several {@link Message}s or/and {@link tech.ydb.topic.read.events.DataReceivedEvent}s can be accepted to commit later
 * all at once.
 * Contains no data references and therefore may also be useful in cases where commit() is called after processing data
 * in an external system.
 *
 * @author Nikolay Perfilov
 */
public interface DeferredCommitter {
    /**
     * Creates a new instance of {@link DeferredCommitter}
     *
     * @return a new instance of {@link DeferredCommitter}
     */
    static DeferredCommitter newInstance() {
        return new DeferredCommitterImpl();
    }

    /**
     * Adds a {@link Message} to commit it later with a commit method
     *
     * @param message a {@link Message} to commit later
     */
    void add(Message message);

    /**
     * Adds a {@link DataReceivedEvent} to commit all its messages later with a commit method
     *
     * @param event a {@link DataReceivedEvent} to commit later
     */
    void add(DataReceivedEvent event);

    /**
     * Commits offset ranges from all {@link Message}s and {@link DataReceivedEvent}s
     * that were added to this DeferredCommitter since last commit
     */
    void commit();
}
