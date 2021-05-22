package com.example.demo;


import com.google.gson.Gson;
import com.sun.org.apache.xalan.internal.xsltc.compiler.util.ErrorMsg;
import com.sun.org.apache.xalan.internal.xsltc.compiler.util.ErrorMsg.*;
import jdk.nashorn.internal.objects.NativeDate;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.common.engine.impl.identity.Authentication;
import org.flowable.engine.*;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.image.ProcessDiagramGenerator;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author pyj
 * @date 2019/10/30
 */
@RestController
@RequestMapping("flowable")
public class TestController {

    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private TaskService taskService;
    @Autowired
    private HistoryService historyService;
    @Autowired
    private RepositoryService repositoryService;
    /*
    @Autowired
    private ProcessEngine processEngine;
*/
    private final ProcessEngine processEngine;

    private final static Logger logger = LoggerFactory.getLogger("rootLogger");
    private NativeDate JsonUtil;


    /**
     * Autowired 只会执行一次，所以成员变量是否加final 吴映香
     * @param processEngine
     */
    @Autowired
    public TestController(ProcessEngine processEngine) {
        this.processEngine = processEngine;
    }

    /**
     * 创建流
     *
     * @param userId
     * @param days
     * @param reason
     * @return
     */
    @GetMapping("add")
    public Map<String, Object> addExpense(String userId, String days, String reason, String tenantId) {
        Map<String, Object> map = new HashMap<>();
        map.put("employee", userId);
        map.put("nrOfHolidays", days);
        map.put("description", reason);

        boolean isAuthUserLogin = true;
        if (StringUtils.isEmpty(Authentication.getAuthenticatedUserId())) {
            Authentication.setAuthenticatedUserId(userId);
            isAuthUserLogin = false;
        }
        logger.info("AuthenticatedUserId is {}, isAuthUserLogin is: {}.", Authentication.getAuthenticatedUserId(), isAuthUserLogin);

        String processDefinitionKey = "holidayRequest";
        String businessKey = "yulj-flowable-demo";
        // Map<String, Object> variables, String tenantId)
        ProcessInstance processInstance = runtimeService.startProcessInstanceByKey(processDefinitionKey, map);

        if (!isAuthUserLogin) {
            Authentication.setAuthenticatedUserId(null);
        }

        Map<String, Object> mapProc = new HashMap<>();
        try {
            mapProc.put("processDefinitionId", processInstance.getProcessDefinitionId());
            mapProc.put("processDefinitionKey", processInstance.getProcessDefinitionKey());
            mapProc.put("processDefinitionVersion", processInstance.getProcessDefinitionVersion());
            mapProc.put("processDefinitionName", processInstance.getProcessDefinitionName());
            mapProc.put("processInstanceId", processInstance.getId());
            String version = repositoryService.getProcessDefinition(processInstance.getProcessDefinitionId()).getEngineVersion();
            mapProc.put("flowableEngineVersion", version);
            mapProc.put("tenantId", processInstance.getTenantId());
            mapProc.put("businessKey", processInstance.getBusinessKey());
            mapProc.put("deploymentId", processInstance.getDeploymentId());
            mapProc.put("processInstanceName", processInstance.getName());
            mapProc.put("startUserId", processInstance.getStartUserId());
            mapProc.put("activityId", processInstance.getActivityId());
        } catch(Exception e) {
            mapProc.put("exception", e.toString());
            logger.error(e.toString());
        }
        finally {

            }

        // return "提交成功,流程ID为：" + processInstance.getId();
        //return new Gson().toJson(mapProc);
        return mapProc;
    }

    /**
     * 获取指定用户组流程任务列表
     *
     * @param group
     * @return
     */
    @GetMapping("list")
    public Object list(String group) {
        List<Task> tasks = taskService.createTaskQuery().taskCandidateGroup(group).list();
        return tasks.toString();
    }

    /**
     * 通过/拒绝任务
     *
     * @param taskId, 每一个activity的主键id,见table:ACT_RU_EXECUTION的ID_
     * @param approved 1 ：true  2：false
     * @return
     */
    @GetMapping("apply")
    public String apply(String taskId, String approved) {
        Task task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) {
            return "流程不存在";
        }
        Map<String, Object> variables = new HashMap<>();
        Boolean apply = approved.equals("1") ? true : false;
        variables.put("approved", apply);
        taskService.complete(taskId, variables);
        return "审批是否通过：" + approved;

    }

    /**
     * 查看历史流程记录
     *
     * @param processInstanceId
     * @return
     */
    @GetMapping("historyList")
    public Object getHistoryList(String processInstanceId) {
        List<HistoricActivityInstance> historicActivityInstances = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId).finished().orderByHistoricActivityInstanceEndTime().asc().list();

        return historicActivityInstances;
    }

    /**
     * 驳回流程实例
     *
     * @param taskId
     * @param targetTaskKey
     * @return
     */
    @GetMapping("rollbask")
    public String rollbaskTask(String taskId, String targetTaskKey) {
        Task currentTask = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (currentTask == null) {
            return "节点不存在";
        }
        List<String> key = new ArrayList<>();
        key.add(currentTask.getTaskDefinitionKey());


        runtimeService.createChangeActivityStateBuilder()
                .processInstanceId(currentTask.getProcessInstanceId())
                .moveActivityIdsToSingleActivityId(key, targetTaskKey)
                .changeState();
        return "驳回成功...";
    }


    /**
     * 终止流程实例
     *
     * @param processInstanceId
     */
    @GetMapping("terminate")
    public String deleteProcessInstanceById(String processInstanceId) {
        if (isExistProcIntRunning(processInstanceId)) {
            runtimeService.deleteProcessInstance(processInstanceId, "terminate!!!");
            return "终止流程实例成功";
        } else {
            return "processInstanceId: [" + processInstanceId + "] is not exits";
        }
        /*
        // ""这个参数本来可以写删除原因
        runtimeService.deleteProcessInstance(processInstanceId, "");
        return "终止流程实例成功";
        */
    }


    /**
     * 挂起流程实例
     *
     * @param processInstanceId 当前流程实例id
     */
    @GetMapping("hangUp")
    public String handUpProcessInstance(String processInstanceId) {
        runtimeService.suspendProcessInstanceById(processInstanceId);
        return "挂起流程成功...";
    }

    /**
     * 恢复（唤醒）被挂起的流程实例
     *
     * @param processInstanceId 流程实例id
     */
    @GetMapping("recovery")
    public String activateProcessInstance(String processInstanceId) {
        runtimeService.activateProcessInstanceById(processInstanceId);
        return "恢复流程成功...";
    }


    /**
     * 判断传入流程实例在运行中是否存在
     *
     * @param processInstanceId
     * @return
     */
    @GetMapping("isExist/running")
    public Boolean isExistProcIntRunning(String processInstanceId) {
        ProcessInstance processInstance = runtimeService.createProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        if (processInstance == null) {
            return false;
        }
        return true;
    }

    /**
     * 判断流程实例在历史记录中是否存在
     * @param processInstanceId
     * @return
     */
    @GetMapping("isExist/history")
    public Map<String, Object> isExistProcInHistory(String processInstanceId) {
        Map<String, Object> map = new HashMap<>();
        boolean isExist = true;
        HistoricProcessInstance historicProcessInstance = historyService.createHistoricProcessInstanceQuery().processInstanceId(processInstanceId).singleResult();
        if (historicProcessInstance == null) {
            isExist = false;
        }
        map.put("isExist", isExist);
        return (map);
    }


    /**
     * 我发起的结束的流程实例列表
     *
     * @param userId
     * @return 流程实例列表
     */
    @GetMapping("myHisProcIns")
    public List<HistoricProcessInstance> getMyHisProcIns(String userId) {
        List<HistoricProcessInstance> list = historyService
                .createHistoricProcessInstanceQuery()
                .startedBy(userId)
                .orderByProcessInstanceStartTime()
                .asc()
                .list();
        return list;
    }

    /**
     * 发起的所有结束的流程实例列表
     *
     * @param
     * @return 流程实例列表
     */
    @GetMapping("allHisProcIns")
    public List<HistoricProcessInstance> getAllHisProcIns() {
        List<HistoricProcessInstance> list = historyService
                .createHistoricProcessInstanceQuery()
                .orderByTenantId()
                .orderByProcessInstanceStartTime()
                .asc()
                .list();
        return list;
    }


    /**
     * 我发起的正在运行的流程实例列表
     *
     * @param userId
     * @return 流程实例列表
     */
    @GetMapping("myRunProcIns")
    public List<ProcessInstance> getMyRunProcIns(String userId) {
        List<ProcessInstance> list = runtimeService
                .createProcessInstanceQuery()
                .startedBy(userId)
                .orderByStartTime()
                .asc()
                .list();
        return list;
    }

    /**
     * 发起的所有正在运行的流程实例列表
     *
     * @param
     * @return 流程实例列表
     */
    @GetMapping("allRunProcIns")
    public List<ProcessInstance> getAllRunProcIns() {
        List<ProcessInstance> list = runtimeService
                .createProcessInstanceQuery()
                .orderByTenantId()
                .orderByStartTime()
                .asc()
                .list();
        return list;
    }



    /**
     * 查询流程图
     *
     * @param httpServletResponse
     * @param processId
     * @throws Exception
     */
    @RequestMapping(value = "processDiagram")
    public boolean genProcessDiagram(HttpServletResponse httpServletResponse, String processId) throws Exception {
        ProcessInstance pi = runtimeService.createProcessInstanceQuery().processInstanceId(processId).singleResult();

        //流程走完的不显示图
        if (pi == null) {
            return false;
        }
        Task task = taskService.createTaskQuery().processInstanceId(pi.getId()).singleResult();
        //使用流程实例ID，查询正在执行的执行对象表，返回流程实例对象
        String InstanceId = task.getProcessInstanceId();
        List<Execution> executions = runtimeService
                .createExecutionQuery()
                .processInstanceId(InstanceId)
                .list();

        logger.info("processId:[" + processId + "], processInstanceId:[" + pi.getId() + "], runningProcessInstanceId:" + InstanceId + "]");
        //得到正在执行的Activity的Id
        List<String> activityIds = new ArrayList<>();
        List<String> exeIds = new ArrayList<>();
        List<String> flows = new ArrayList<>();
        for (Execution exe : executions) {
            List<String> ids = runtimeService.getActiveActivityIds(exe.getId());
            activityIds.addAll(ids);
            exeIds.add(exe.getId());
        }

        logger.info("activityIds:" + activityIds.toString());
        logger.info("exeIds:" + exeIds.toString());

        //获取流程图
        BpmnModel bpmnModel = repositoryService.getBpmnModel(pi.getProcessDefinitionId());
        ProcessEngineConfiguration engconf = processEngine.getProcessEngineConfiguration();
        ProcessDiagramGenerator diagramGenerator = engconf.getProcessDiagramGenerator();
        InputStream in = diagramGenerator.generateDiagram(
                bpmnModel,
                "png",
                activityIds,
                flows,
                engconf.getActivityFontName(),
                engconf.getLabelFontName(),
                engconf.getAnnotationFontName(),
                engconf.getClassLoader(),
                1.0,
                true);

        OutputStream out = null;
        byte[] buf = new byte[1024];
        int length = 0;
        boolean isReady = false;
        try {
            out = httpServletResponse.getOutputStream();
            while ((length = in.read(buf)) != -1) {
                out.write(buf, 0, length);
                isReady = true;
            }
        } finally {
            if (in != null) {
                in.close();
            }
            if (out != null) {
                out.close();
            }
        }

        return isReady;
    }

    @GetMapping("procDefList")
    public List<ProcessDefinition> queryProcDefList(String tenantId) {
        // 租户流程定义列表查询
        return repositoryService.createProcessDefinitionQuery().processDefinitionTenantId(tenantId).list();
    }

    @GetMapping("procInsList")
    public List<ProcessInstance> queryProcInsListList(String tenantId) {
        // 租户流程实例列表查询
        return runtimeService.createProcessInstanceQuery().processInstanceTenantId(tenantId).list();
    }

    /**
     * 更换流程部署租户
     * @param deploymentId 部署ID
     * @param tenantId 新租户
     * @return
     */
    @PutMapping(value = "/chProcDeployTenant/{deploymentId}/{tenantId}")
    public String changeProcessDeployTenant(@PathVariable(value = "deploymentId") String deploymentId, @PathVariable(value = "tenantId") String tenantId) {
        Deployment deployment = repositoryService.createDeploymentQuery().deploymentId(deploymentId).singleResult();
        String originTenantId = deployment.getTenantId();
        repositoryService.changeDeploymentTenantId(deploymentId, tenantId);
        String newTenantId = repositoryService.createDeploymentQuery().deploymentId(deploymentId).singleResult().getTenantId();
        String newTenantId2 = deployment.getTenantId();
        String res = "origin tenant id: " + originTenantId + ", new tenant id: " + newTenantId + ", object quota tenant id: " + newTenantId2;
        return res;
    }

    /**
     * 更换流程定义租户
     * @param deploymentId 部署ID
     * @param tenantId 新租户
     * @return
     */
    @PutMapping(value = "/chProcDefTenant/{deploymentId}/{tenantId}")
    public String changeProcessDefineTenant(@PathVariable(value = "deploymentId") String deploymentId, @PathVariable(value = "tenantId") String tenantId) {
        Deployment deployment = null;
        ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery().deploymentId(deploymentId).singleResult();

        String originTenantId = processDefinition.getTenantId();
        return originTenantId;
        /*
        //repositoryService.ten
        repositoryService.changeDeploymentTenantId(deploymentId, tenantId);
        String newTenantId = repositoryService.createDeploymentQuery().deploymentId(deploymentId).singleResult().getTenantId();
        String newTenantId2 = deployment.getTenantId();
        String res = "origin tenant id: " + originTenantId + ", new tenant id: " + newTenantId + ", object quota tenant id: " + newTenantId2;
        return res;
        */

    }
}


/*

DeploymentBuilder deploymentBuilder = repositoryService.createDeployment();
deploymentBuilder.name("yby的流程")
       .addClasspathResource("second_approve.bpmn20.xml")
       .addClasspathResource("another_approve.bpmn20.xml");
Deployment deployment = deploymentBuilder.deploy();
String deploymentId = deployment.getId();

// 使用repositoryService查询单个部署对象
DeploymentQuery deploymentQuery = repositoryService.createDeploymentQuery();
Deployment deployment1 = deploymentQuery.deploymentId(deploymentId).singleResult();

// 使用repositoryService查询多个部署对象
List<Deployment> deploymentList = deploymentQuery
                .orderByDeploymenTime()
                .asc()
                .listPage(0,100);

// 使用repositoryService查询单个流程实例
ProcessDefinition processDefinition = repositoryService
                .createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .singleResult();

// 使用repositoryService查询多个流程实例
List<ProcessDefinition> processDefinitionList = repositoryService
                .createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .listPage(0,100);
for(ProcessDefinition processDefinition: processDefinitionList){
      logger.info("流程定义为:{},版本为:{}",processDefinition.getName(),processDefinition.getVersion());
}
 */


/*

startProcessInstanceByKey 设置TenantId
repositoryService.createDeployment()
                    .name(modelData.getName())
                    .addBytes(processName, bpmnBytes)
                    // 设置租户
                    .tenantId(tenantId)
                    .deploy();
 */