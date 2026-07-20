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

import java.util.List;
import java.util.Optional;

public interface EquipmentOperationsService {

    List<EquipmentModel> findModels(String model, String category, String workshopId, String teamId);

    Optional<EquipmentStatus> findDeviceStatus(String deviceId, String workshopId, String teamId);

    Optional<FaultCode> findFaultCode(String faultCode, String model);

    Optional<MaintenanceWorkOrder> findWorkOrder(String workOrderNo, String workshopId, String teamId);

    Optional<SparePartInventory> findSparePart(String partNo, String workshopId, String teamId);

    Optional<MaintenancePlan> findMaintenancePlan(String model, String deviceId, String workshopId, String teamId);
}
