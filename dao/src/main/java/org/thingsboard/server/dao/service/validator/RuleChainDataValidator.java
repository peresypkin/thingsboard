/**
 * Copyright © 2016-2023 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.dao.service.validator;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.plugin.ComponentSingletonSupport;
import org.thingsboard.server.common.data.rule.NodeConnectionInfo;
import org.thingsboard.server.common.data.rule.RuleChain;
import org.thingsboard.server.common.data.rule.RuleChainMetaData;
import org.thingsboard.server.common.data.rule.RuleChainType;
import org.thingsboard.server.common.data.rule.RuleNode;
import org.thingsboard.server.common.data.util.ReflectionUtils;
import org.thingsboard.server.dao.exception.DataValidationException;
import org.thingsboard.server.dao.rule.RuleChainDao;
import org.thingsboard.server.dao.rule.RuleChainService;
import org.thingsboard.server.dao.service.ConstraintValidator;
import org.thingsboard.server.dao.service.DataValidator;
import org.thingsboard.server.dao.tenant.TenantService;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Slf4j
public class RuleChainDataValidator extends DataValidator<RuleChain> {

    @Autowired
    private RuleChainDao ruleChainDao;

    @Autowired
    @Lazy
    private RuleChainService ruleChainService;

    @Autowired
    private TenantService tenantService;

    @Override
    protected void validateCreate(TenantId tenantId, RuleChain data) {
        validateNumberOfEntitiesPerTenant(tenantId, EntityType.RULE_CHAIN);
    }

    @Override
    protected void validateDataImpl(TenantId tenantId, RuleChain ruleChain) {
        if (StringUtils.isEmpty(ruleChain.getName())) {
            throw new DataValidationException("Rule chain name should be specified!");
        }
        if (ruleChain.getType() == null) {
            ruleChain.setType(RuleChainType.CORE);
        }
        if (ruleChain.getTenantId() == null || ruleChain.getTenantId().isNullUid()) {
            throw new DataValidationException("Rule chain should be assigned to tenant!");
        }
        if (!tenantService.tenantExists(ruleChain.getTenantId())) {
            throw new DataValidationException("Rule chain is referencing to non-existent tenant!");
        }
        if (ruleChain.isRoot() && RuleChainType.CORE.equals(ruleChain.getType())) {
            RuleChain rootRuleChain = ruleChainService.getRootTenantRuleChain(ruleChain.getTenantId());
            if (rootRuleChain != null && !rootRuleChain.getId().equals(ruleChain.getId())) {
                throw new DataValidationException("Another root rule chain is present in scope of current tenant!");
            }
        }
        if (ruleChain.isRoot() && RuleChainType.EDGE.equals(ruleChain.getType())) {
            RuleChain edgeTemplateRootRuleChain = ruleChainService.getEdgeTemplateRootRuleChain(ruleChain.getTenantId());
            if (edgeTemplateRootRuleChain != null && !edgeTemplateRootRuleChain.getId().equals(ruleChain.getId())) {
                throw new DataValidationException("Another edge template root rule chain is present in scope of current tenant!");
            }
        }
    }

    public static void validateMetaData(RuleChainMetaData ruleChainMetaData) {
        ConstraintValidator.validateFields(ruleChainMetaData);
        ruleChainMetaData.getNodes().forEach(RuleChainDataValidator::validateRuleNode);
        if (CollectionUtils.isNotEmpty(ruleChainMetaData.getConnections())) {
            validateCircles(ruleChainMetaData.getConnections());
        }
    }

    public static void validateRuleNode(RuleNode ruleNode) {
        String errorPrefix = "'" + ruleNode.getName() + "' node configuration is invalid: ";
        ConstraintValidator.validateFields(ruleNode, errorPrefix);
        Object nodeConfig;
        try {
            Class<Object> nodeConfigType = ReflectionUtils.getAnnotationProperty(ruleNode.getType(),
                    "org.thingsboard.rule.engine.api.RuleNode", "configClazz");
            nodeConfig = JacksonUtil.treeToValue(ruleNode.getConfiguration(), nodeConfigType);
        } catch (Exception e) {
            log.warn("Failed to validate node configuration: {}", ExceptionUtils.getRootCauseMessage(e));
            return;
        }
        ConstraintValidator.validateFields(nodeConfig, errorPrefix);

        ComponentSingletonSupport nodeConfigType = null;
        try {
            nodeConfigType = ReflectionUtils.getAnnotationProperty(ruleNode.getType(),
                    "org.thingsboard.rule.engine.api.RuleNode", "singleton");
        } catch (Exception e) {
            log.warn("Failed to validate singleton mode: {}", ExceptionUtils.getRootCauseMessage(e));
            return;
        }
        if (ComponentSingletonSupport.NOT_SUPPORTED.equals(nodeConfigType) && ruleNode.isSingletonMode()) {
            throw new DataValidationException(String.format("Singleton mode not supported for [%s].", ruleNode.getType()));
        }

        if (ComponentSingletonSupport.ONLY_SINGLETON.equals(nodeConfigType) && !ruleNode.isSingletonMode()) {
            throw new DataValidationException(String.format("Supported only singleton mode for [%s].", ruleNode.getType()));
        }
    }

    private static void validateCircles(List<NodeConnectionInfo> connectionInfos) {
        Map<Integer, Set<Integer>> connectionsMap = new HashMap<>();
        for (NodeConnectionInfo nodeConnection : connectionInfos) {
            if (nodeConnection.getFromIndex() == nodeConnection.getToIndex()) {
                throw new DataValidationException("Can't create the relation to yourself.");
            }
            connectionsMap
                    .computeIfAbsent(nodeConnection.getFromIndex(), from -> new HashSet<>())
                    .add(nodeConnection.getToIndex());
        }
        connectionsMap.keySet().forEach(key -> validateCircles(key, connectionsMap.get(key), connectionsMap));
    }

    private static void validateCircles(int from, Set<Integer> toList, Map<Integer, Set<Integer>> connectionsMap) {
        if (toList == null) {
            return;
        }
        for (Integer to : toList) {
            if (from == to) {
                throw new DataValidationException("Can't create circling relations in rule chain.");
            }
            validateCircles(from, connectionsMap.get(to), connectionsMap);
        }
    }

}
