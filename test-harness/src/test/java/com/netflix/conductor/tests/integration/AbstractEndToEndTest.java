package com.netflix.conductor.tests.integration;

import com.netflix.conductor.common.metadata.tasks.TaskDef;
import com.netflix.conductor.common.metadata.workflow.TaskType;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.metadata.workflow.WorkflowTask;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

public abstract class AbstractEndToEndTest {

    private static final String TASK_DEFINITION_PREFIX = "task_";
    private static final String DEFAULT_DESCRIPTION = "description";
    // Represents null value deserialized from the redis in memory db
    private static final String DEFAULT_NULL_VALUE = "null";

    protected WorkflowTask createWorkflowTask(String name) {
        WorkflowTask workflowTask = new WorkflowTask();
        workflowTask.setName(name);
        workflowTask.setWorkflowTaskType(TaskType.SIMPLE);
        workflowTask.setTaskReferenceName(name);
        workflowTask.setDescription(getDefaultDescription(name));
        workflowTask.setDynamicTaskNameParam(DEFAULT_NULL_VALUE);
        workflowTask.setCaseValueParam(DEFAULT_NULL_VALUE);
        workflowTask.setCaseExpression(DEFAULT_NULL_VALUE);
        workflowTask.setDynamicForkTasksParam(DEFAULT_NULL_VALUE);
        workflowTask.setDynamicForkTasksInputParamName(DEFAULT_NULL_VALUE);
        workflowTask.setSink(DEFAULT_NULL_VALUE);
        return workflowTask;
    }

    protected TaskDef createTaskDefinition(String name) {
        TaskDef taskDefinition = new TaskDef();
        taskDefinition.setName(name);
        return taskDefinition;
    }

    protected WorkflowDef createWorkflowDefinition(String workflowName) {
        WorkflowDef workflowDefinition = new WorkflowDef();
        workflowDefinition.setName(workflowName);
        workflowDefinition.setDescription(getDefaultDescription(workflowName));
        workflowDefinition.setFailureWorkflow(DEFAULT_NULL_VALUE);
        return workflowDefinition;
    }

    protected List<TaskDef> createAndRegisterTaskDefinitions(String prefixTaskDefinition, int numberOfTaskDefinitions) {
        String prefix = Optional.ofNullable(prefixTaskDefinition).orElse(TASK_DEFINITION_PREFIX);
        List<TaskDef> definitions = new LinkedList<>();
        for (int i = 0; i < numberOfTaskDefinitions; i++) {
            TaskDef def = new TaskDef(prefix + i, "task " + i + DEFAULT_DESCRIPTION);
            def.setTimeoutPolicy(TaskDef.TimeoutPolicy.RETRY);
            definitions.add(def);
        }
        this.registerTaskDefinitions(definitions);
        return definitions;
    }

    private String getDefaultDescription(String nameResource) {
        return nameResource + " " + DEFAULT_DESCRIPTION;
    }

    protected abstract void registerTaskDefinitions(List<TaskDef> taskDefinitionList);
}
