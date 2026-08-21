package com.mysite.core.schedulers;

import java.util.Date;

import org.apache.sling.api.resource.ResourceResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.granite.taskmanagement.Task;
import com.adobe.granite.taskmanagement.TaskManager;
import com.adobe.granite.taskmanagement.TaskManagerException;

/**
 * Raises AEM Inbox notifications (Granite tasks) for the administrators group.
 * Isolated so a notification failure never propagates into the export flow.
 */
final class WorkfrontInboxNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(WorkfrontInboxNotifier.class);

    private static final String TASK_TYPE = "Notification";
    private static final String ADMINISTRATORS = "administrators";

    private WorkfrontInboxNotifier() {
        // static utility
    }

    /**
     * Creates a Notification task assigned to the administrators group.
     *
     * @param resolver    a resolver with task-management permissions
     * @param title       short notification title
     * @param description detailed message
     * @param contentPath the content/CSV path the notification relates to
     */
    static void notifyFailure(final ResourceResolver resolver, final String title,
                              final String description, final String contentPath) {
        try {
            final TaskManager taskManager = resolver.adaptTo(TaskManager.class);
            if (taskManager == null) {
                LOG.warn("Could not adapt ResourceResolver to TaskManager; skipping Inbox notification");
                return;
            }
            // TaskManager.createTask(String) does not return a Task; a task must be
            // created via the factory, populated, then persisted with saveTask.
            final Task task = taskManager.getTaskManagerFactory().newTask(TASK_TYPE);
            task.setName(title);
            task.setDescription(description);
            task.setCurrentAssignee(ADMINISTRATORS);
            task.setContentPath(contentPath);
            task.setProperty("dueDate", new Date());
            taskManager.saveTask(task);
            LOG.info("Raised AEM Inbox notification for administrators: {}", title);
        } catch (final TaskManagerException e) {
            LOG.warn("Failed to raise AEM Inbox notification '{}'", title, e);
        } catch (final Exception e) {
            LOG.warn("Unexpected error raising AEM Inbox notification '{}'", title, e);
        }
    }
}
