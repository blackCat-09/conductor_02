/**
 * Copyright 2016 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
/**
 *
 */
package com.netflix.conductor.tests.integration;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.netflix.conductor.bootstrap.BootstrapModule;
import com.netflix.conductor.bootstrap.ModulesProvider;
import com.netflix.conductor.client.grpc.MetadataClient;
import com.netflix.conductor.client.grpc.TaskClient;
import com.netflix.conductor.client.grpc.WorkflowClient;
import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.metadata.tasks.Task.Status;
import com.netflix.conductor.common.metadata.tasks.TaskDef;
import com.netflix.conductor.common.metadata.tasks.TaskDef.TimeoutPolicy;
import com.netflix.conductor.common.metadata.tasks.TaskResult;
import com.netflix.conductor.common.metadata.workflow.StartWorkflowRequest;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.metadata.workflow.WorkflowTask;
import com.netflix.conductor.common.run.SearchResult;
import com.netflix.conductor.common.run.Workflow;
import com.netflix.conductor.common.run.Workflow.WorkflowStatus;
import com.netflix.conductor.common.run.WorkflowSummary;
import com.netflix.conductor.elasticsearch.ElasticSearchConfiguration;
import com.netflix.conductor.elasticsearch.EmbeddedElasticSearch;
import com.netflix.conductor.elasticsearch.EmbeddedElasticSearchProvider;
import com.netflix.conductor.grpc.server.GRPCServer;
import com.netflix.conductor.grpc.server.GRPCServerConfiguration;
import com.netflix.conductor.grpc.server.GRPCServerProvider;
import com.netflix.conductor.tests.utils.TestEnvironment;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @author Viren
 *
 */
public class End2EndGrpcTests extends AbstractEndToEndTest {
    private static TaskClient taskClient;
    private static WorkflowClient workflowClient;
    private static MetadataClient metadataClient;
    private static EmbeddedElasticSearch search;

    @BeforeClass
    public static void setup() throws Exception {
        TestEnvironment.setup();
        System.setProperty(GRPCServerConfiguration.ENABLED_PROPERTY_NAME, "true");
        System.setProperty(ElasticSearchConfiguration.EMBEDDED_PORT_PROPERTY_NAME, "9202");
        System.setProperty(ElasticSearchConfiguration.ELASTIC_SEARCH_URL_PROPERTY_NAME, "localhost:9302");

        Injector bootInjector = Guice.createInjector(new BootstrapModule());
        Injector serverInjector = Guice.createInjector(bootInjector.getInstance(ModulesProvider.class).get());

        search = serverInjector.getInstance(EmbeddedElasticSearchProvider.class).get().get();
        search.start();

        Optional<GRPCServer> server = serverInjector.getInstance(GRPCServerProvider.class).get();
        assertTrue("failed to instantiate GRPCServer", server.isPresent());
        server.get().start();

        taskClient = new TaskClient("localhost", 8090);
        workflowClient = new WorkflowClient("localhost", 8090);
        metadataClient = new MetadataClient("localhost", 8090);
    }

    @AfterClass
    public static void teardown() throws Exception {
        TestEnvironment.teardown();
        search.stop();
    }

    @Test
    public void testAll() throws Exception {
        assertNotNull(taskClient);
        List<TaskDef> defs = new LinkedList<>();
        for (int i = 0; i < 5; i++) {
            TaskDef def = new TaskDef("t" + i, "task " + i);
            def.setTimeoutPolicy(TimeoutPolicy.RETRY);
            defs.add(def);
        }
        metadataClient.registerTaskDefs(defs);

        for (int i = 0; i < 5; i++) {
            final String taskName = "t" + i;
            TaskDef def = metadataClient.getTaskDef(taskName);
            assertNotNull(def);
            assertEquals(taskName, def.getName());
        }

        WorkflowDef def = createWorkflowDefinition("test");
        WorkflowTask t0 = createWorkflowTask("t0");
        WorkflowTask t1 = createWorkflowTask("t1");


        def.getTasks().add(t0);
        def.getTasks().add(t1);

        metadataClient.registerWorkflowDef(def);
        WorkflowDef found = metadataClient.getWorkflowDef(def.getName(), null);
        assertNotNull(found);
        assertEquals(def, found);

        String correlationId = "test_corr_id";
        StartWorkflowRequest startWf = new StartWorkflowRequest();
        startWf.setName(def.getName());
        startWf.setCorrelationId(correlationId);

        String workflowId = workflowClient.startWorkflow(startWf);
        assertNotNull(workflowId);
        System.out.println("Started workflow id=" + workflowId);

        Workflow wf = workflowClient.getWorkflow(workflowId, false);
        assertEquals(0, wf.getTasks().size());
        assertEquals(workflowId, wf.getWorkflowId());

        wf = workflowClient.getWorkflow(workflowId, true);
        assertNotNull(wf);
        assertEquals(WorkflowStatus.RUNNING, wf.getStatus());
        assertEquals(1, wf.getTasks().size());
        assertEquals(t0.getTaskReferenceName(), wf.getTasks().get(0).getReferenceTaskName());
        assertEquals(workflowId, wf.getWorkflowId());

        List<String> runningIds = workflowClient.getRunningWorkflow(def.getName(), def.getVersion());
        assertNotNull(runningIds);
        assertEquals(1, runningIds.size());
        assertEquals(workflowId, runningIds.get(0));

        List<Task> polled = taskClient.batchPollTasksByTaskType("non existing task", "test", 1, 100);
        assertNotNull(polled);
        assertEquals(0, polled.size());

        polled = taskClient.batchPollTasksByTaskType(t0.getName(), "test", 1, 100);
        assertNotNull(polled);
        assertEquals(1, polled.size());
        assertEquals(t0.getName(), polled.get(0).getTaskDefName());
        Task task = polled.get(0);

        Boolean acked = taskClient.ack(task.getTaskId(), "test");
        assertNotNull(acked);
        assertTrue(acked);

        task.getOutputData().put("key1", "value1");
        task.setStatus(Status.COMPLETED);
        taskClient.updateTask(new TaskResult(task));

        polled = taskClient.batchPollTasksByTaskType(t0.getName(), "test", 1, 100);
        assertNotNull(polled);
        assertTrue(polled.toString(), polled.isEmpty());

        wf = workflowClient.getWorkflow(workflowId, true);
        assertNotNull(wf);
        assertEquals(WorkflowStatus.RUNNING, wf.getStatus());
        assertEquals(2, wf.getTasks().size());
        assertEquals(t0.getTaskReferenceName(), wf.getTasks().get(0).getReferenceTaskName());
        assertEquals(t1.getTaskReferenceName(), wf.getTasks().get(1).getReferenceTaskName());
        assertEquals(Status.COMPLETED, wf.getTasks().get(0).getStatus());
        assertEquals(Status.SCHEDULED, wf.getTasks().get(1).getStatus());

        Task taskById = taskClient.getTaskDetails(task.getTaskId());
        assertNotNull(taskById);
        assertEquals(task.getTaskId(), taskById.getTaskId());


        List<Task> getTasks = taskClient.getPendingTasksByType(t0.getName(), null, 1);
        assertNotNull(getTasks);
        assertEquals(0, getTasks.size());        //getTasks only gives pending tasks


        getTasks = taskClient.getPendingTasksByType(t1.getName(), null, 1);
        assertNotNull(getTasks);
        assertEquals(1, getTasks.size());


        Task pending = taskClient.getPendingTaskForWorkflow(workflowId, t1.getTaskReferenceName());
        assertNotNull(pending);
        assertEquals(t1.getTaskReferenceName(), pending.getReferenceTaskName());
        assertEquals(workflowId, pending.getWorkflowInstanceId());

        Thread.sleep(1000);
        SearchResult<WorkflowSummary> searchResult = workflowClient.search("workflowType='" + def.getName() + "'");
        assertNotNull(searchResult);
        assertEquals(1, searchResult.getTotalHits());

        workflowClient.terminateWorkflow(workflowId, "terminate reason");
        wf = workflowClient.getWorkflow(workflowId, true);
        assertNotNull(wf);
        assertEquals(WorkflowStatus.TERMINATED, wf.getStatus());

        workflowClient.restart(workflowId);
        wf = workflowClient.getWorkflow(workflowId, true);
        assertNotNull(wf);
        assertEquals(WorkflowStatus.RUNNING, wf.getStatus());
        assertEquals(1, wf.getTasks().size());
    }

    @Test
    public void testEphemeralWorkflowsWithStoredTasks() throws Exception {
        createAndRegisterTaskDefinitions("storedTaskDef", 5);

        String workflowName = "testEphemeralWorkflow";
        WorkflowDef workflowDefinition = createWorkflowDefinition(workflowName);

        WorkflowTask workflowTask1 = createWorkflowTask("storedTaskDef1");
        WorkflowTask workflowTask2 = createWorkflowTask("storedTaskDef2");

        workflowDefinition.getTasks().add(workflowTask1);
        workflowDefinition.getTasks().add(workflowTask2);

        String workflowExecutionName = "ephemeralWorkflow";
        StartWorkflowRequest workflowRequest = new StartWorkflowRequest()
                .withName(workflowExecutionName)
                .withWorkflowDef(workflowDefinition);

        String workflowId = workflowClient.startWorkflow(workflowRequest);
        assertNotNull(workflowId);

        Workflow workflow = workflowClient.getWorkflow(workflowId, true);
        WorkflowDef ephemeralWorkflow = workflow.getWorkflowDefinition();
        assertNotNull(ephemeralWorkflow);
        assertEquals(workflowDefinition, ephemeralWorkflow);
    }

    @Test
    public void testEphemeralWorkflowsWithEphemeralTasks() throws Exception {
        String workflowName = "testEphemeralWorkflowWithEphemeralTasks";
        WorkflowDef workflowDefinition = createWorkflowDefinition(workflowName);

        WorkflowTask workflowTask1 = createWorkflowTask("ephemeralTask1");
        TaskDef taskDefinition1 = createTaskDefinition("ephemeralTaskDef1");
        workflowTask1.setTaskDefinition(taskDefinition1);

        WorkflowTask workflowTask2 = createWorkflowTask("ephemeralTask2");
        TaskDef taskDefinition2 = createTaskDefinition("ephemeralTaskDef2");
        workflowTask2.setTaskDefinition(taskDefinition2);

        workflowDefinition.getTasks().add(workflowTask1);
        workflowDefinition.getTasks().add(workflowTask2);

        String workflowExecutionName = "ephemeralWorkflowWithEphemeralTasks";
        StartWorkflowRequest workflowRequest = new StartWorkflowRequest()
                .withName(workflowExecutionName)
                .withWorkflowDef(workflowDefinition);

        String workflowId = workflowClient.startWorkflow(workflowRequest);
        assertNotNull(workflowId);

        Workflow workflow = workflowClient.getWorkflow(workflowId, true);
        WorkflowDef ephemeralWorkflow = workflow.getWorkflowDefinition();
        assertNotNull(ephemeralWorkflow);
        assertEquals(workflowDefinition, ephemeralWorkflow);

        List<WorkflowTask> ephemeralTasks = ephemeralWorkflow.getTasks();
        assertEquals(2, ephemeralTasks.size());
        for (WorkflowTask ephemeralTask : ephemeralTasks) {
            assertNotNull(ephemeralTask.getTaskDefinition());
        }

    }

    @Override
    protected void registerTaskDefinitions(List<TaskDef> taskDefinitionList) {
        metadataClient.registerTaskDefs(taskDefinitionList);
    }
}
