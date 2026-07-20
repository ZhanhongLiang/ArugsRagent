/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.mcp.equipment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

class InMemoryEquipmentOperationsServiceTest {

    private final InMemoryEquipmentOperationsService service = new InMemoryEquipmentOperationsService();

    @Test
    void findsModelWithinRequestedOrganizationScope() {
        assertThat(service.findModels("CNC-MT-1060", null, "MACHINING", "TEAM-M1"))
                .singleElement()
                .extracting(EquipmentModel::applicableLine)
                .isEqualTo("阀体与支架加工线");

        assertThat(service.findModels("CNC-MT-1060", null, "ASSEMBLY", null)).isEmpty();
    }

    @Test
    void returnsLiveStatusAndRejectsWrongScope() {
        assertThat(service.findDeviceStatus("CNC-02", "MACHINING", "TEAM-M1"))
                .get()
                .extracting(EquipmentStatus::currentAlarm)
                .isEqualTo("CNC-203 伺服跟随误差超限");

        assertThat(service.findDeviceStatus("CNC-02", "ASSEMBLY", null)).isEmpty();
    }

    @Test
    void returnsFaultWorkOrderPartAndMaintenancePlan() {
        assertThat(service.findFaultCode("ROB-302", "ROB-IR-20"))
                .get()
                .extracting(FaultCode::dangerLevel)
                .isEqualTo("高");
        assertThat(service.findWorkOrder("WO-20260719-001", "MACHINING", "TEAM-M1")).isPresent();
        assertThat(service.findSparePart("SP-CONV-VFD-02", "ASSEMBLY", "TEAM-A"))
                .get()
                .extracting(SparePartInventory::availableQuantity)
                .isEqualTo(5);
        assertThat(service.findMaintenancePlan(null, "ROB-02", "ASSEMBLY", "TEAM-A"))
                .get()
                .extracting(MaintenancePlan::model)
                .isEqualTo("ROB-IR-20");
    }

    @Test
    void providesMaintenancePlanAndSparePartCoverageForEveryModel() {
        List<EquipmentModel> models = service.findModels(null, null, null, null);

        assertThat(models).hasSize(15);
        assertThat(models)
                .allSatisfy(model -> assertThat(service.findMaintenancePlan(
                                model.model(), null, model.workshopId(), model.teamId()))
                        .isPresent());
        assertThat(models)
                .allSatisfy(model -> assertThat(model.recommendedSpareParts())
                        .allSatisfy(partNo -> assertThat(service.findSparePart(
                                partNo, model.workshopId(), model.teamId())).isPresent()));
    }
}
