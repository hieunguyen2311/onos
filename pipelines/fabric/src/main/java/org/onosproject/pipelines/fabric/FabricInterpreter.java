/*
 * Copyright 2017-present Open Networking Foundation
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

package org.onosproject.pipelines.fabric;

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.onlab.packet.DeserializationException;
import org.onlab.packet.Ethernet;
import org.onlab.util.ImmutableByteSequence;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Port;
import org.onosproject.net.PortNumber;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.driver.AbstractHandlerBehaviour;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.criteria.Criterion;
import org.onosproject.net.flow.instructions.Instructions;
import org.onosproject.net.packet.DefaultInboundPacket;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.OutboundPacket;
import org.onosproject.net.pi.model.PiMatchFieldId;
import org.onosproject.net.pi.model.PiPipeconf;
import org.onosproject.net.pi.model.PiPipeconfId;
import org.onosproject.net.pi.model.PiPipelineInterpreter;
import org.onosproject.net.pi.model.PiTableId;
import org.onosproject.net.pi.runtime.PiAction;
import org.onosproject.net.pi.runtime.PiControlMetadata;
import org.onosproject.net.pi.runtime.PiPacketOperation;
import org.onosproject.net.pi.service.PiPipeconfService;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.lang.String.format;
import static java.util.stream.Collectors.toList;
import static org.onlab.util.ImmutableByteSequence.copyFrom;
import static org.onosproject.net.PortNumber.CONTROLLER;
import static org.onosproject.net.PortNumber.FLOOD;
import static org.onosproject.net.flow.instructions.Instruction.Type.OUTPUT;
import static org.onosproject.net.pi.model.PiPacketOperationType.PACKET_OUT;
import static org.onosproject.net.pi.model.PiPipeconf.ExtensionType.CPU_PORT_TXT;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Interpreter for fabric pipeline.
 */
public class FabricInterpreter extends AbstractHandlerBehaviour
        implements PiPipelineInterpreter {

    private final Logger log = getLogger(getClass());

    public static final int PORT_BITWIDTH = 9;

    private static final ImmutableBiMap<Integer, PiTableId> TABLE_ID_MAP =
            ImmutableBiMap.<Integer, PiTableId>builder()
                    // Filtering
                    .put(0, FabricConstants.FABRIC_INGRESS_FILTERING_INGRESS_PORT_VLAN)
                    .put(1, FabricConstants.FABRIC_INGRESS_FILTERING_FWD_CLASSIFIER)
                    // Forwarding
                    .put(2, FabricConstants.FABRIC_INGRESS_FORWARDING_MPLS)
                    .put(3, FabricConstants.FABRIC_INGRESS_FORWARDING_ROUTING_V4)
                    .put(4, FabricConstants.FABRIC_INGRESS_FORWARDING_ROUTING_V6)
                    .put(5, FabricConstants.FABRIC_INGRESS_FORWARDING_BRIDGING)
                    .put(6, FabricConstants.FABRIC_INGRESS_FORWARDING_ACL)
                    // Next
                    .put(7, FabricConstants.FABRIC_INGRESS_NEXT_VLAN_META)
                    .put(8, FabricConstants.FABRIC_INGRESS_NEXT_SIMPLE)
                    .put(9, FabricConstants.FABRIC_INGRESS_NEXT_HASHED)
                    .put(10, FabricConstants.FABRIC_INGRESS_NEXT_MULTICAST)
                    .put(11, FabricConstants.FABRIC_EGRESS_EGRESS_NEXT_EGRESS_VLAN)
                    .build();

    private static final Set<PiTableId> FILTERING_CTRL_TBLS =
            ImmutableSet.of(FabricConstants.FABRIC_INGRESS_FILTERING_INGRESS_PORT_VLAN,
                            FabricConstants.FABRIC_INGRESS_FILTERING_FWD_CLASSIFIER);
    private static final Set<PiTableId> FORWARDING_CTRL_TBLS =
            ImmutableSet.of(FabricConstants.FABRIC_INGRESS_FORWARDING_MPLS,
                            FabricConstants.FABRIC_INGRESS_FORWARDING_ROUTING_V4,
                            FabricConstants.FABRIC_INGRESS_FORWARDING_ROUTING_V6,
                            FabricConstants.FABRIC_INGRESS_FORWARDING_BRIDGING,
                            FabricConstants.FABRIC_INGRESS_FORWARDING_ACL);
    private static final Set<PiTableId> NEXT_CTRL_TBLS =
            ImmutableSet.of(FabricConstants.FABRIC_INGRESS_NEXT_VLAN_META,
                            FabricConstants.FABRIC_INGRESS_NEXT_SIMPLE,
                            FabricConstants.FABRIC_INGRESS_NEXT_HASHED,
                            FabricConstants.FABRIC_INGRESS_NEXT_MULTICAST);
    private static final Set<PiTableId> E_NEXT_CTRL_TBLS =
            ImmutableSet.of(FabricConstants.FABRIC_EGRESS_EGRESS_NEXT_EGRESS_VLAN);

    private static final ImmutableMap<Criterion.Type, PiMatchFieldId> CRITERION_MAP =
            ImmutableMap.<Criterion.Type, PiMatchFieldId>builder()
                    .put(Criterion.Type.IN_PORT, FabricConstants.STANDARD_METADATA_INGRESS_PORT)
                    .put(Criterion.Type.ETH_DST_MASKED, FabricConstants.HDR_ETHERNET_DST_ADDR)
                    .put(Criterion.Type.ETH_SRC_MASKED, FabricConstants.HDR_ETHERNET_SRC_ADDR)
                    .put(Criterion.Type.ETH_TYPE, FabricConstants.HDR_VLAN_TAG_ETHER_TYPE)
                    .put(Criterion.Type.MPLS_LABEL, FabricConstants.HDR_MPLS_LABEL)
                    .put(Criterion.Type.VLAN_VID, FabricConstants.HDR_VLAN_TAG_VLAN_ID)
                    .put(Criterion.Type.IPV4_DST, FabricConstants.HDR_IPV4_DST_ADDR)
                    .put(Criterion.Type.IPV4_SRC, FabricConstants.HDR_IPV4_SRC_ADDR)
                    .put(Criterion.Type.IPV6_DST, FabricConstants.HDR_IPV6_DST_ADDR)
                    .put(Criterion.Type.IP_PROTO, FabricConstants.FABRIC_METADATA_IP_PROTO)
                    .put(Criterion.Type.ICMPV6_TYPE, FabricConstants.HDR_ICMP_ICMP_TYPE)
                    .put(Criterion.Type.ICMPV6_CODE, FabricConstants.HDR_ICMP_ICMP_CODE)
                    .build();

    private static final ImmutableMap<PiMatchFieldId, Criterion.Type> INVERSE_CRITERION_MAP =
            ImmutableMap.<PiMatchFieldId, Criterion.Type>builder()
                    .put(FabricConstants.STANDARD_METADATA_INGRESS_PORT, Criterion.Type.IN_PORT)
                    .put(FabricConstants.HDR_ETHERNET_DST_ADDR, Criterion.Type.ETH_DST_MASKED)
                    .put(FabricConstants.HDR_ETHERNET_SRC_ADDR, Criterion.Type.ETH_SRC_MASKED)
                    .put(FabricConstants.HDR_VLAN_TAG_ETHER_TYPE, Criterion.Type.ETH_TYPE)
                    .put(FabricConstants.HDR_MPLS_LABEL, Criterion.Type.MPLS_LABEL)
                    .put(FabricConstants.HDR_VLAN_TAG_VLAN_ID, Criterion.Type.VLAN_VID)
                    .put(FabricConstants.HDR_IPV4_DST_ADDR, Criterion.Type.IPV4_DST)
                    .put(FabricConstants.HDR_IPV4_SRC_ADDR, Criterion.Type.IPV4_SRC)
                    .put(FabricConstants.HDR_IPV6_DST_ADDR, Criterion.Type.IPV6_DST)
                    // FIXME: might be incorrect if we inverse the map....
                    .put(FabricConstants.FABRIC_METADATA_L4_SRC_PORT, Criterion.Type.UDP_SRC)
                    .put(FabricConstants.FABRIC_METADATA_L4_DST_PORT, Criterion.Type.UDP_DST)
                    .put(FabricConstants.FABRIC_METADATA_IP_PROTO, Criterion.Type.IP_PROTO)
                    .put(FabricConstants.HDR_ICMP_ICMP_TYPE, Criterion.Type.ICMPV6_TYPE)
                    .put(FabricConstants.HDR_ICMP_ICMP_CODE, Criterion.Type.ICMPV6_CODE)
                    .build();

    private static final PiAction NOACTION = PiAction.builder().withId(
            FabricConstants.NO_ACTION).build();

    private static final ImmutableMap<PiTableId, PiAction> DEFAULT_ACTIONS =
            ImmutableMap.<PiTableId, PiAction>builder()
                    .put(FabricConstants.FABRIC_INGRESS_FORWARDING_ROUTING_V4, NOACTION)
                    .build();

    @Override
    public Optional<PiMatchFieldId> mapCriterionType(Criterion.Type type) {
        return Optional.ofNullable(CRITERION_MAP.get(type));
    }

    @Override
    public Optional<Criterion.Type> mapPiMatchFieldId(PiMatchFieldId fieldId) {
        return Optional.ofNullable(INVERSE_CRITERION_MAP.get(fieldId));
    }

    @Override
    public Optional<PiTableId> mapFlowRuleTableId(int flowRuleTableId) {
        return Optional.ofNullable(TABLE_ID_MAP.get(flowRuleTableId));
    }

    @Override
    public Optional<Integer> mapPiTableId(PiTableId piTableId) {
        return Optional.ofNullable(TABLE_ID_MAP.inverse().get(piTableId));
    }

    @Override
    public PiAction mapTreatment(TrafficTreatment treatment, PiTableId piTableId)
            throws PiInterpreterException {

        if (FILTERING_CTRL_TBLS.contains(piTableId)) {
            return FabricTreatmentInterpreter.mapFilteringTreatment(treatment, piTableId);
        } else if (FORWARDING_CTRL_TBLS.contains(piTableId)) {
            return FabricTreatmentInterpreter.mapForwardingTreatment(treatment, piTableId);
        } else if (NEXT_CTRL_TBLS.contains(piTableId)) {
            return FabricTreatmentInterpreter.mapNextTreatment(treatment, piTableId);
        } else if (E_NEXT_CTRL_TBLS.contains(piTableId)) {
            return FabricTreatmentInterpreter.mapEgressNextTreatment(treatment, piTableId);
        } else {
            throw new PiInterpreterException(String.format("Table %s unsupported", piTableId));
        }
    }

    private PiPacketOperation createPiPacketOperation(DeviceId deviceId, ByteBuffer data, long portNumber)
            throws PiInterpreterException {
        PiControlMetadata metadata = createPacketMetadata(portNumber);
        return PiPacketOperation.builder()
                .forDevice(deviceId)
                .withType(PACKET_OUT)
                .withData(copyFrom(data))
                .withMetadatas(ImmutableList.of(metadata))
                .build();
    }

    private PiControlMetadata createPacketMetadata(long portNumber) throws PiInterpreterException {
        try {
            return PiControlMetadata.builder()
                    .withId(FabricConstants.EGRESS_PORT)
                    .withValue(copyFrom(portNumber).fit(PORT_BITWIDTH))
                    .build();
        } catch (ImmutableByteSequence.ByteSequenceTrimException e) {
            throw new PiInterpreterException(format(
                    "Port number %d too big, %s", portNumber, e.getMessage()));
        }
    }

    @Override
    public Collection<PiPacketOperation> mapOutboundPacket(OutboundPacket packet)
            throws PiInterpreterException {
        DeviceId deviceId = packet.sendThrough();
        TrafficTreatment treatment = packet.treatment();

        // fabric.p4 supports only OUTPUT instructions.
        List<Instructions.OutputInstruction> outInstructions = treatment
                .allInstructions()
                .stream()
                .filter(i -> i.type().equals(OUTPUT))
                .map(i -> (Instructions.OutputInstruction) i)
                .collect(toList());

        if (treatment.allInstructions().size() != outInstructions.size()) {
            // There are other instructions that are not of type OUTPUT.
            throw new PiInterpreterException("Treatment not supported: " + treatment);
        }

        ImmutableList.Builder<PiPacketOperation> builder = ImmutableList.builder();
        for (Instructions.OutputInstruction outInst : outInstructions) {
            if (outInst.port().isLogical() && !outInst.port().equals(FLOOD)) {
                throw new PiInterpreterException(format(
                        "Output on logical port '%s' not supported", outInst.port()));
            } else if (outInst.port().equals(FLOOD)) {
                // Since fabric.p4 does not support flooding, we create a packet
                // operation for each switch port.
                final DeviceService deviceService = handler().get(DeviceService.class);
                for (Port port : deviceService.getPorts(packet.sendThrough())) {
                    builder.add(createPiPacketOperation(deviceId, packet.data(), port.number().toLong()));
                }
            } else {
                builder.add(createPiPacketOperation(deviceId, packet.data(), outInst.port().toLong()));
            }
        }
        return builder.build();
    }

    @Override
    public InboundPacket mapInboundPacket(PiPacketOperation packetIn) throws PiInterpreterException {
        // Assuming that the packet is ethernet, which is fine since fabric.p4
        // can deparse only ethernet packets.
        DeviceId deviceId = packetIn.deviceId();
        Ethernet ethPkt;
        try {
            ethPkt = Ethernet.deserializer().deserialize(packetIn.data().asArray(), 0,
                                                         packetIn.data().size());
        } catch (DeserializationException dex) {
            throw new PiInterpreterException(dex.getMessage());
        }

        // Returns the ingress port packet metadata.
        Optional<PiControlMetadata> packetMetadata = packetIn.metadatas()
                .stream().filter(m -> m.id().equals(FabricConstants.INGRESS_PORT))
                .findFirst();

        if (packetMetadata.isPresent()) {
            ImmutableByteSequence portByteSequence = packetMetadata.get().value();
            short s = portByteSequence.asReadOnlyBuffer().getShort();
            ConnectPoint receivedFrom = new ConnectPoint(deviceId, PortNumber.portNumber(s));
            ByteBuffer rawData = ByteBuffer.wrap(packetIn.data().asArray());
            return new DefaultInboundPacket(receivedFrom, ethPkt, rawData);
        } else {
            throw new PiInterpreterException(format(
                    "Missing metadata '%s' in packet-in received from '%s': %s",
                    FabricConstants.INGRESS_PORT, deviceId, packetIn));
        }
    }

    @Override
    public Optional<PiAction> getOriginalDefaultAction(PiTableId tableId) {
        return Optional.ofNullable(DEFAULT_ACTIONS.get(tableId));
    }

    @Override
    public Optional<Integer> mapLogicalPortNumber(PortNumber port) {
        if (!port.equals(CONTROLLER)) {
            return Optional.empty();
        }
        // This is probably brittle, but needed to dynamically get the CPU port
        // for different platforms.
        final DeviceId deviceId = data().deviceId();
        final PiPipeconfService pipeconfService = handler().get(
                PiPipeconfService.class);
        final PiPipeconfId pipeconfId = pipeconfService
                .ofDevice(deviceId).orElse(null);
        if (pipeconfId == null ||
                !pipeconfService.getPipeconf(pipeconfId).isPresent()) {
            log.error("Unable to get pipeconf of {} - BUG?");
            return Optional.empty();
        }
        final PiPipeconf pipeconf = pipeconfService.getPipeconf(pipeconfId).get();
        if (!pipeconf.extension(CPU_PORT_TXT).isPresent()) {
            log.error("Missing {} extension from pipeconf {}",
                      CPU_PORT_TXT, pipeconfId);
            return Optional.empty();
        }
        return Optional.ofNullable(
                readCpuPort(pipeconf.extension(CPU_PORT_TXT).get(),
                            pipeconfId));
    }

    private Integer readCpuPort(InputStream stream, PiPipeconfId pipeconfId) {
        try {
            final BufferedReader buff = new BufferedReader(
                    new InputStreamReader(stream));
            final String str = buff.readLine();
            buff.close();
            if (str == null) {
                log.error("Empty CPU port file for {}", pipeconfId);
                return null;
            }
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException e) {
                log.error("Invalid CPU port for {}: {}", pipeconfId, str);
                return null;
            }
        } catch (IOException e) {
            log.error("Unable to read CPU port file of {}: {}",
                      pipeconfId, e.getMessage());
            return null;
        }
    }
}
