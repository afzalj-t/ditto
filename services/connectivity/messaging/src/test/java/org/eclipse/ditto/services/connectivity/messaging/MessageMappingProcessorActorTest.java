/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.connectivity.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.eclipse.ditto.services.connectivity.messaging.MessageMappingProcessorActor.filterAcknowledgements;
import static org.eclipse.ditto.services.connectivity.messaging.TestConstants.disableLogging;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.eclipse.ditto.json.JsonFactory;
import org.eclipse.ditto.json.JsonFieldSelector;
import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.json.JsonParseOptions;
import org.eclipse.ditto.json.JsonPointer;
import org.eclipse.ditto.json.JsonValue;
import org.eclipse.ditto.model.base.acks.AcknowledgementLabel;
import org.eclipse.ditto.model.base.acks.AcknowledgementRequest;
import org.eclipse.ditto.model.base.acks.DittoAcknowledgementLabel;
import org.eclipse.ditto.model.base.acks.FilteredAcknowledgementRequest;
import org.eclipse.ditto.model.base.auth.AuthorizationContext;
import org.eclipse.ditto.model.base.auth.AuthorizationModelFactory;
import org.eclipse.ditto.model.base.auth.AuthorizationSubject;
import org.eclipse.ditto.model.base.auth.DittoAuthorizationContextType;
import org.eclipse.ditto.model.base.common.HttpStatusCode;
import org.eclipse.ditto.model.base.headers.DittoHeaderDefinition;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.connectivity.ConnectionSignalIdEnforcementFailedException;
import org.eclipse.ditto.model.connectivity.ConnectivityModelFactory;
import org.eclipse.ditto.model.connectivity.Enforcement;
import org.eclipse.ditto.model.connectivity.EnforcementFactoryFactory;
import org.eclipse.ditto.model.connectivity.EnforcementFilter;
import org.eclipse.ditto.model.connectivity.EnforcementFilterFactory;
import org.eclipse.ditto.model.connectivity.MessageMappingFailedException;
import org.eclipse.ditto.model.connectivity.Target;
import org.eclipse.ditto.model.connectivity.Topic;
import org.eclipse.ditto.model.placeholders.PlaceholderFunctionSignatureInvalidException;
import org.eclipse.ditto.model.placeholders.UnresolvedPlaceholderException;
import org.eclipse.ditto.model.things.Thing;
import org.eclipse.ditto.model.things.ThingId;
import org.eclipse.ditto.protocoladapter.JsonifiableAdaptable;
import org.eclipse.ditto.protocoladapter.ProtocolFactory;
import org.eclipse.ditto.protocoladapter.TopicPath;
import org.eclipse.ditto.services.connectivity.messaging.BaseClientActor.PublishMappedMessage;
import org.eclipse.ditto.services.models.connectivity.ExternalMessage;
import org.eclipse.ditto.services.models.connectivity.ExternalMessageFactory;
import org.eclipse.ditto.services.models.connectivity.OutboundSignal;
import org.eclipse.ditto.services.models.connectivity.OutboundSignalFactory;
import org.eclipse.ditto.signals.acks.base.Acknowledgement;
import org.eclipse.ditto.signals.acks.base.Acknowledgements;
import org.eclipse.ditto.signals.base.Signal;
import org.eclipse.ditto.signals.commands.things.exceptions.ThingNotAccessibleException;
import org.eclipse.ditto.signals.commands.things.modify.DeleteThing;
import org.eclipse.ditto.signals.commands.things.modify.DeleteThingResponse;
import org.eclipse.ditto.signals.commands.things.modify.ModifyAttribute;
import org.eclipse.ditto.signals.commands.things.modify.ModifyAttributeResponse;
import org.eclipse.ditto.signals.commands.things.query.RetrieveThing;
import org.eclipse.ditto.signals.commands.things.query.RetrieveThingResponse;
import org.eclipse.ditto.signals.commands.thingsearch.subscription.CancelSubscription;
import org.junit.Test;

import akka.actor.ActorRef;
import akka.testkit.TestProbe;
import akka.testkit.javadsl.TestKit;

/**
 * Tests {@link MessageMappingProcessorActor}.
 */
public final class MessageMappingProcessorActorTest extends AbstractMessageMappingProcessorActorTest {

    @Test
    public void testRequestedAcknowledgementFilter() {
        // GIVEN
        final String requestedAcks = DittoHeaderDefinition.REQUESTED_ACKS.getKey();
        final AcknowledgementRequest twinPersistedAckRequest =
                AcknowledgementRequest.of(DittoAcknowledgementLabel.TWIN_PERSISTED);
        final Signal<?> signal = DeleteThing.of(ThingId.of("thing:id"), DittoHeaders.empty());
        final Signal<?> signalWithRequestedAcks = DeleteThing.of(ThingId.of("thing:id"), DittoHeaders.newBuilder()
                .acknowledgementRequest(twinPersistedAckRequest)
                .build());

        // WHEN/THEN

        final Signal<?> notFilteredSignal =
                filterAcknowledgements(signal, "fn:filter('2+2','ne','5')");
        assertThat(notFilteredSignal.getDittoHeaders()).doesNotContainKey(requestedAcks);

        final Signal<?> filteredSignal =
                filterAcknowledgements(signal, "fn:filter('2+2','eq','5')");
        assertThat(filteredSignal.getDittoHeaders()).contains(Map.entry(requestedAcks, "[]"));

        assertThatExceptionOfType(PlaceholderFunctionSignatureInvalidException.class).isThrownBy(() ->
                filterAcknowledgements(signal, "fn:filter('2','+','2','eq','5')")
        );

        final Signal<?> defaultValueSetSignal =
                filterAcknowledgements(signal, "fn:default('[\"twin-persisted\"]')");
        assertThat(defaultValueSetSignal.getDittoHeaders().getAcknowledgementRequests())
                .containsExactly(twinPersistedAckRequest);

        final Signal<?> transformedSignal =
                filterAcknowledgements(signalWithRequestedAcks, "fn:filter('2+2','eq','5')|fn:default('[\"custom\"]')");
        assertThat(transformedSignal.getDittoHeaders().getAcknowledgementRequests())
                .containsExactly(AcknowledgementRequest.parseAcknowledgementRequest("custom"));
    }


    @Test
    public void testExternalMessageInDittoProtocolIsProcessedWithDefaultMapper() {
        testExternalMessageInDittoProtocolIsProcessed(null);
    }

    @Test
    public void testExternalMessageInDittoProtocolIsProcessedWithCustomMapper() {
        testExternalMessageInDittoProtocolIsProcessed(null, ADD_HEADER_MAPPER);
    }

    @Test
    public void testThingIdEnforcementExternalMessageInDittoProtocolIsProcessed() {
        final Enforcement mqttEnforcement =
                ConnectivityModelFactory.newEnforcement("{{ test:placeholder }}",
                        "mqtt/topic/{{ thing:namespace }}/{{ thing:name }}");
        final EnforcementFilterFactory<String, CharSequence> factory =
                EnforcementFactoryFactory.newEnforcementFilterFactory(mqttEnforcement, new TestPlaceholder());
        final EnforcementFilter<CharSequence> enforcementFilter = factory.getFilter("mqtt/topic/my/thing");
        testExternalMessageInDittoProtocolIsProcessed(enforcementFilter);
    }

    @Test
    public void testThingIdEnforcementExternalMessageInDittoProtocolIsProcessedExpectErrorResponse() {
        disableLogging(actorSystem);
        final Enforcement mqttEnforcement =
                ConnectivityModelFactory.newEnforcement("{{ test:placeholder }}",
                        "mqtt/topic/{{ thing:namespace }}/{{ thing:name }}");
        final EnforcementFilterFactory<String, CharSequence> factory =
                EnforcementFactoryFactory.newEnforcementFilterFactory(mqttEnforcement, new TestPlaceholder());
        final EnforcementFilter<CharSequence> enforcementFilter = factory.getFilter("some/invalid/target");
        testExternalMessageInDittoProtocolIsProcessed(enforcementFilter, false, null,
                r -> assertThat(r.getDittoRuntimeException())
                        .isInstanceOf(ConnectionSignalIdEnforcementFailedException.class)
        );
    }

    @Test
    public void testMappingFailedExpectErrorResponseWitMapperId() {
        disableLogging(actorSystem);
        testExternalMessageInDittoProtocolIsProcessed(null, false, FAULTY_MAPPER,
                r -> {
                    assertThat(r.getDittoRuntimeException()).isInstanceOf(MessageMappingFailedException.class);
                    assertThat(r.getDittoRuntimeException().getDescription())
                            .hasValueSatisfying(desc -> assertThat(desc).contains(FAULTY_MAPPER));
                }
        );
    }

    @Test
    public void testSignalEnrichment() {
        // GIVEN: test probe actor started with configured values
        final TestProbe proxyActorProbe = TestProbe.apply("mockProxyActorProbe", actorSystem);
        setUpProxyActor(proxyActorProbe.ref());

        new TestKit(actorSystem) {{
            // GIVEN: MessageMappingProcessor started with a test probe as the configured enrichment provider
            final ActorRef underTest = createMessageMappingProcessorActor(this);

            // WHEN: a signal is received with 2 targets, one with enrichment and one without
            final JsonFieldSelector extraFields = JsonFieldSelector.newInstance("attributes/x", "attributes/y");
            final AuthorizationSubject targetAuthSubject = AuthorizationSubject.newInstance("target:auth-subject");
            final AuthorizationSubject targetAuthSubjectWithoutIssuer =
                    AuthorizationSubject.newInstance("auth-subject");
            final Target targetWithEnrichment = ConnectivityModelFactory.newTargetBuilder()
                    .address("target/address")
                    .authorizationContext(AuthorizationContext.newInstance(DittoAuthorizationContextType.UNSPECIFIED,
                            targetAuthSubject))
                    .topics(ConnectivityModelFactory.newFilteredTopicBuilder(Topic.TWIN_EVENTS)
                            .withExtraFields(extraFields)
                            .build())
                    .build();
            final Target targetWithoutEnrichment = ConnectivityModelFactory.newTargetBuilder(targetWithEnrichment)
                    .topics(Topic.TWIN_EVENTS)
                    .build();
            final Signal<?> signal = TestConstants.thingModified(Collections.emptyList());
            final OutboundSignal outboundSignal = OutboundSignalFactory.newOutboundSignal(signal,
                    Arrays.asList(targetWithEnrichment, targetWithoutEnrichment));
            underTest.tell(outboundSignal, getRef());

            // THEN: Receive a RetrieveThing command from the facade.
            final RetrieveThing retrieveThing = proxyActorProbe.expectMsgClass(RetrieveThing.class);
            assertThat(retrieveThing.getSelectedFields()).contains(extraFields);
            assertThat(retrieveThing.getDittoHeaders().getAuthorizationContext()).containsExactly(targetAuthSubject,
                    targetAuthSubjectWithoutIssuer);

            final JsonObject extra = JsonObject.newBuilder().set("/attributes/x", 5).build();
            proxyActorProbe.reply(
                    RetrieveThingResponse.of(retrieveThing.getEntityId(), extra, retrieveThing.getDittoHeaders()));

            // THEN: a mapped signal without enrichment arrives first
            final PublishMappedMessage publishMappedMessage = expectMsgClass(PublishMappedMessage.class);
            int i = 0;
            expectPublishedMappedMessage(publishMappedMessage, i++, signal, targetWithoutEnrichment);

            // THEN: Receive an outbound signal with extra fields.
            expectPublishedMappedMessage(publishMappedMessage, i, signal, targetWithEnrichment,
                    mapped -> assertThat(mapped.getAdaptable().getPayload().getExtra()).contains(extra));
        }};
    }

    @Test
    public void testSignalEnrichmentWithPayloadMappedTargets() {
        resetActorSystemWithCachingSignalEnrichmentProvider();
        final TestProbe proxyActorProbe = TestProbe.apply("mockConciergeForwarder", actorSystem);
        setUpProxyActor(proxyActorProbe.ref());

        new TestKit(actorSystem) {{
            // GIVEN: MessageMappingProcessor started with a test probe as the configured enrichment provider
            final ActorRef underTest = createMessageMappingProcessorActor(this);

            // WHEN: a signal is received with 6 targets:
            //  - 1 with enrichment w/o payload mapping
            //  - 1 with enrichment with 1 payload mapping
            //  - 1 with enrichment with 2 payload mappings
            //  - 1 w/o enrichment w/o payload mapping
            //  - 1 w/o enrichment with 1 payload mapping
            //  - 1 w/o enrichment with 2 payload mappings
            final JsonFieldSelector extraFields = JsonFactory.newFieldSelector("attributes/x,attributes/y",
                    JsonParseOptions.newBuilder().withoutUrlDecoding().build());
            final AuthorizationSubject targetAuthSubject = AuthorizationSubject.newInstance("target:auth-subject");
            final AuthorizationSubject targetAuthSubjectWithoutIssuer =
                    AuthorizationSubject.newInstance("auth-subject");
            final Target targetWithEnrichment = ConnectivityModelFactory.newTargetBuilder()
                    .address("target/address")
                    .authorizationContext(AuthorizationContext.newInstance(DittoAuthorizationContextType.UNSPECIFIED,
                            targetAuthSubject))
                    .topics(ConnectivityModelFactory.newFilteredTopicBuilder(Topic.TWIN_EVENTS)
                            .withExtraFields(extraFields)
                            .build())
                    .build();
            final Target targetWithEnrichmentAnd1PayloadMapper = ConnectivityModelFactory.newTargetBuilder()
                    .address("target/address/mapped/1")
                    .authorizationContext(AuthorizationContext.newInstance(DittoAuthorizationContextType.UNSPECIFIED,
                            targetAuthSubject))
                    .topics(ConnectivityModelFactory.newFilteredTopicBuilder(Topic.TWIN_EVENTS)
                            .withExtraFields(extraFields)
                            .build())
                    .payloadMapping(ConnectivityModelFactory.newPayloadMapping(ADD_HEADER_MAPPER))
                    .build();
            final Target targetWithEnrichmentAnd2PayloadMappers = ConnectivityModelFactory.newTargetBuilder()
                    .address("target/address/mapped/2")
                    .authorizationContext(AuthorizationContext.newInstance(DittoAuthorizationContextType.UNSPECIFIED,
                            targetAuthSubject))
                    .topics(ConnectivityModelFactory.newFilteredTopicBuilder(Topic.TWIN_EVENTS)
                            .withExtraFields(extraFields)
                            .build())
                    .payloadMapping(ConnectivityModelFactory.newPayloadMapping(DUPLICATING_MAPPER, ADD_HEADER_MAPPER))
                    .build();
            final Target targetWithoutEnrichment = ConnectivityModelFactory.newTargetBuilder(targetWithEnrichment)
                    .address("target/address/without/enrichment")
                    .topics(Topic.TWIN_EVENTS)
                    .build();
            final Target targetWithoutEnrichmentAnd1PayloadMapper =
                    ConnectivityModelFactory.newTargetBuilder(targetWithEnrichment)
                            .address("target/address/without/enrichment/with/1/payloadmapper")
                            .topics(Topic.TWIN_EVENTS)
                            .payloadMapping(ConnectivityModelFactory.newPayloadMapping(ADD_HEADER_MAPPER))
                            .build();
            final Target targetWithoutEnrichmentAnd2PayloadMappers =
                    ConnectivityModelFactory.newTargetBuilder(targetWithEnrichment)
                            .address("target/address/without/enrichment/with/2/payloadmappers")
                            .topics(Topic.TWIN_EVENTS)
                            .payloadMapping(
                                    ConnectivityModelFactory.newPayloadMapping(ADD_HEADER_MAPPER, DUPLICATING_MAPPER))
                            .build();
            final Signal<?> signal = TestConstants.thingModified(Collections.emptyList())
                    .setRevision(8L); // important to set revision to same value as cache lookup retrieves
            final OutboundSignal outboundSignal = OutboundSignalFactory.newOutboundSignal(signal, Arrays.asList(
                    targetWithEnrichment,
                    targetWithoutEnrichment,
                    targetWithEnrichmentAnd1PayloadMapper,
                    targetWithoutEnrichmentAnd1PayloadMapper,
                    targetWithEnrichmentAnd2PayloadMappers,
                    targetWithoutEnrichmentAnd2PayloadMappers)
            );
            underTest.tell(outboundSignal, getRef());
            // THEN: Receive a RetrieveThing command from the facade.
            final RetrieveThing retrieveThing = proxyActorProbe.expectMsgClass(RetrieveThing.class);
            final JsonFieldSelector extraFieldsWithAdditionalCachingSelectedOnes = JsonFactory.newFieldSelectorBuilder()
                    .addPointers(extraFields)
                    .addFieldDefinition(Thing.JsonFields.REVISION) // additionally always select the revision
                    .build();
            assertThat(retrieveThing.getSelectedFields()).contains(extraFieldsWithAdditionalCachingSelectedOnes);
            assertThat(retrieveThing.getDittoHeaders().getAuthorizationContext()).containsExactly(targetAuthSubject,
                    targetAuthSubjectWithoutIssuer);
            final JsonObject extra = JsonObject.newBuilder()
                    .set("/attributes/x", 5)
                    .build();
            final JsonObject extraForCachingFacade = JsonObject.newBuilder()
                    .set("_revision", 8)
                    .setAll(extra)
                    .build();
            proxyActorProbe.reply(RetrieveThingResponse.of(retrieveThing.getEntityId(), extraForCachingFacade,
                    retrieveThing.getDittoHeaders()));

            // THEN: mapped messages arrive in a batch.
            final PublishMappedMessage publishMappedMessage = expectMsgClass(PublishMappedMessage.class);
            int i = 0;

            // THEN: the first mapped signal is without enrichment
            expectPublishedMappedMessage(publishMappedMessage, i++, signal, targetWithoutEnrichment,
                    mapped -> assertThat(mapped.getExternalMessage().getHeaders()).containsOnlyKeys("content-type")
            );

            // THEN: the second mapped signal is without enrichment and applied 1 payload mapper arrives
            expectPublishedMappedMessage(publishMappedMessage, i++, signal,
                    targetWithoutEnrichmentAnd1PayloadMapper,
                    mapped -> assertThat(mapped.getExternalMessage().getHeaders())
                            .contains(AddHeaderMessageMapper.OUTBOUND_HEADER)
            );

            // THEN: a mapped signal without enrichment and applied 2 payload mappers arrives causing 3 messages
            //  as 1 mapper duplicates the message
            expectPublishedMappedMessage(publishMappedMessage, i++, signal,
                    targetWithoutEnrichmentAnd2PayloadMappers,
                    mapped -> assertThat(mapped.getExternalMessage().getHeaders())
                            .contains(AddHeaderMessageMapper.OUTBOUND_HEADER)
            );
            expectPublishedMappedMessage(publishMappedMessage, i++, signal,
                    targetWithoutEnrichmentAnd2PayloadMappers,
                    mapped -> assertThat(mapped.getExternalMessage().getHeaders()).containsOnlyKeys("content-type")
            );
            expectPublishedMappedMessage(publishMappedMessage, i++, signal,
                    targetWithoutEnrichmentAnd2PayloadMappers,
                    mapped -> assertThat(mapped.getExternalMessage().getHeaders()).containsOnlyKeys("content-type")
            );

            // THEN: Receive an outbound signal with extra fields.
            expectPublishedMappedMessage(publishMappedMessage, i++, signal, targetWithEnrichment,
                    mapped -> assertThat(mapped.getAdaptable().getPayload().getExtra()).contains(extra),
                    mapped -> assertThat(mapped.getExternalMessage().getHeaders()).containsOnlyKeys("content-type")
            );

            // THEN: Receive an outbound signal with extra fields and with mapped payload.
            expectPublishedMappedMessage(publishMappedMessage, i++, signal,
                    targetWithEnrichmentAnd1PayloadMapper,
                    mapped -> assertThat(mapped.getAdaptable().getPayload().getExtra()).contains(extra),
                    mapped -> assertThat(mapped.getExternalMessage().getHeaders())
                            .contains(AddHeaderMessageMapper.OUTBOUND_HEADER)
            );

            // THEN: a mapped signal with enrichment and applied 2 payload mappers arrives causing 3 messages
            //  as 1 mapper duplicates the message
            expectPublishedMappedMessage(publishMappedMessage, i++, signal,
                    targetWithEnrichmentAnd2PayloadMappers,
                    mapped -> assertThat(mapped.getAdaptable().getPayload().getExtra()).contains(extra),
                    mapped -> assertThat(mapped.getExternalMessage().getHeaders()).containsOnlyKeys("content-type")
            );
            expectPublishedMappedMessage(publishMappedMessage, i++, signal,
                    targetWithEnrichmentAnd2PayloadMappers,
                    mapped -> assertThat(mapped.getAdaptable().getPayload().getExtra()).contains(extra),
                    mapped -> assertThat(mapped.getExternalMessage().getHeaders()).containsOnlyKeys("content-type")
            );
            expectPublishedMappedMessage(publishMappedMessage, i, signal,
                    targetWithEnrichmentAnd2PayloadMappers,
                    mapped -> assertThat(mapped.getAdaptable().getPayload().getExtra()).contains(extra),
                    mapped -> assertThat(mapped.getExternalMessage().getHeaders())
                            .contains(AddHeaderMessageMapper.OUTBOUND_HEADER)
            );
        }};
    }

    @Test
    public void testReplacementOfPlaceholders() {
        final String correlationId = UUID.randomUUID().toString();
        final AuthorizationContext contextWithPlaceholders =
                AuthorizationModelFactory.newAuthContext(DittoAuthorizationContextType.UNSPECIFIED,
                        AuthorizationModelFactory.newAuthSubject(
                                "integration:{{header:correlation-id}}:hub-{{   header:content-type   }}"),
                        AuthorizationModelFactory.newAuthSubject(
                                "integration:{{header:content-type}}:hub-{{ header:correlation-id }}"));

        final AuthorizationContext expectedAuthContext = TestConstants.Authorization.withUnprefixedSubjects(
                AuthorizationModelFactory.newAuthContext(
                        DittoAuthorizationContextType.UNSPECIFIED,
                        AuthorizationModelFactory.newAuthSubject(
                                "integration:" + correlationId + ":hub-application/json"),
                        AuthorizationModelFactory.newAuthSubject("integration:application/json:hub-" + correlationId)));

        testMessageMapping(correlationId, contextWithPlaceholders, ModifyAttribute.class, modifyAttribute -> {
            assertThat(modifyAttribute.getType()).isEqualTo(ModifyAttribute.TYPE);
            assertThat(modifyAttribute.getDittoHeaders().getCorrelationId()).contains(correlationId);
            assertThat(modifyAttribute.getDittoHeaders().getAuthorizationContext().getAuthorizationSubjects())
                    .isEqualTo(expectedAuthContext.getAuthorizationSubjects());

            // mapped by source <- {{ request:subjectId }}
            assertThat(modifyAttribute.getDittoHeaders().get("source"))
                    .contains("integration:" + correlationId + ":hub-application/json");
        });
    }

    @Test
    public void testHeadersOnTwinTopicPathCombinationError() {
        final String correlationId = UUID.randomUUID().toString();

        final AuthorizationContext authorizationContext = AuthorizationModelFactory.newAuthContext(
                DittoAuthorizationContextType.UNSPECIFIED,
                AuthorizationModelFactory.newAuthSubject("integration:" + correlationId + ":hub-application/json"));

        new TestKit(actorSystem) {{

            final ActorRef messageMappingProcessorActor = createMessageMappingProcessorActor(this);

            // WHEN: message sent valid topic and invalid topic+path combination
            final String messageContent = "{  \n" +
                    "   \"topic\":\"Testspace/octopus/things/twin/commands/retrieve\",\n" +
                    "   \"path\":\"/policyId\",\n" +
                    "   \"headers\":{  \n" +
                    "      \"correlation-id\":\"" + correlationId + "\"\n" +
                    "   }\n" +
                    "}";
            final ExternalMessage inboundMessage =
                    ExternalMessageFactory.newExternalMessageBuilder(Collections.emptyMap())
                            .withInternalHeaders(HEADERS_WITH_REPLY_INFORMATION)
                            .withText(messageContent)
                            .withAuthorizationContext(authorizationContext)
                            .build();

            final TestProbe collectorProbe = TestProbe.apply("collector", actorSystem);
            messageMappingProcessorActor.tell(inboundMessage, collectorProbe.ref());

            // THEN: resulting error response retains the correlation ID
            final OutboundSignal outboundSignal =
                    expectMsgClass(PublishMappedMessage.class).getOutboundSignal();
            assertThat(outboundSignal)
                    .extracting(o -> o.getSource().getDittoHeaders().getCorrelationId()
                            .orElseThrow(() -> new AssertionError("correlation-id not found")))
                    .isEqualTo(correlationId);
        }};
    }

    @Test
    public void testMessageWithoutCorrelationId() {

        final AuthorizationContext connectionAuthContext = AuthorizationModelFactory.newAuthContext(
                DittoAuthorizationContextType.UNSPECIFIED,
                AuthorizationModelFactory.newAuthSubject("integration:application/json:hub"),
                AuthorizationModelFactory.newAuthSubject("integration:hub-application/json"));

        final AuthorizationContext expectedMessageAuthContext =
                TestConstants.Authorization.withUnprefixedSubjects(connectionAuthContext);

        testMessageMappingWithoutCorrelationId(connectionAuthContext, ModifyAttribute.class, modifyAttribute -> {
            assertThat(modifyAttribute.getType()).isEqualTo(ModifyAttribute.TYPE);
            assertThat(modifyAttribute.getDittoHeaders().getAuthorizationContext()).isEqualTo(
                    expectedMessageAuthContext);

        });
    }

    @Test
    public void testTopicOnLiveTopicPathCombinationError() {
        final String correlationId = UUID.randomUUID().toString();

        final AuthorizationContext authorizationContext = AuthorizationModelFactory.newAuthContext(
                DittoAuthorizationContextType.UNSPECIFIED,
                AuthorizationModelFactory.newAuthSubject("integration:" + correlationId + ":hub-application/json"));

        new TestKit(actorSystem) {{

            final ActorRef messageMappingProcessorActor = createMessageMappingProcessorActor(this);

            // WHEN: message sent with valid topic and invalid topic+path combination
            final String topicPrefix = "Testspace/octopus/things/live/";
            final String topic = topicPrefix + "commands/retrieve";
            final String path = "/policyId";
            final String messageContent = "{  \n" +
                    "   \"topic\":\"" + topic + "\",\n" +
                    "   \"path\":\"" + path + "\"\n" +
                    "}";
            final ExternalMessage inboundMessage =
                    ExternalMessageFactory.newExternalMessageBuilder(Collections.emptyMap())
                            .withInternalHeaders(HEADERS_WITH_REPLY_INFORMATION)
                            .withText(messageContent)
                            .withAuthorizationContext(authorizationContext)
                            .build();

            final TestProbe collectorProbe = TestProbe.apply("collector", actorSystem);
            messageMappingProcessorActor.tell(inboundMessage, collectorProbe.ref());

            // THEN: resulting error response retains the topic including thing ID and channel
            final ExternalMessage outboundMessage =
                    expectMsgClass(PublishMappedMessage.class).getOutboundSignal().first().getExternalMessage();
            assertThat(outboundMessage)
                    .extracting(e -> JsonFactory.newObject(e.getTextPayload().orElse("{}"))
                            .getValue("topic"))
                    .isEqualTo(Optional.of(JsonValue.of(topicPrefix + "errors")));
        }};
    }

    @Test
    public void testUnknownPlaceholdersExpectUnresolvedPlaceholderException() {
        disableLogging(actorSystem);

        final String placeholderKey = "header:unknown";
        final String placeholder = "{{" + placeholderKey + "}}";
        final AuthorizationContext contextWithUnknownPlaceholder = AuthorizationModelFactory.newAuthContext(
                DittoAuthorizationContextType.UNSPECIFIED,
                AuthorizationModelFactory.newAuthSubject("integration:" + placeholder));

        testMessageMapping(UUID.randomUUID().toString(), contextWithUnknownPlaceholder,
                PublishMappedMessage.class, error -> {
                    final OutboundSignal.Mapped outboundSignal = error.getOutboundSignal().first();
                    final UnresolvedPlaceholderException exception = UnresolvedPlaceholderException.fromMessage(
                            outboundSignal.getExternalMessage()
                                    .getTextPayload()
                                    .orElseThrow(() -> new IllegalArgumentException("payload was empty")),
                            DittoHeaders.of(outboundSignal.getExternalMessage().getHeaders()));
                    assertThat(exception.getMessage()).contains(placeholderKey);
                });
    }

    @Test
    public void testCommandResponseIsProcessed() {
        new TestKit(actorSystem) {{
            final ActorRef messageMappingProcessorActor = createMessageMappingProcessorActor(this);

            final String correlationId = UUID.randomUUID().toString();
            final ModifyAttributeResponse commandResponse =
                    ModifyAttributeResponse.modified(KNOWN_THING_ID, JsonPointer.of("foo"),
                            HEADERS_WITH_REPLY_INFORMATION.toBuilder().correlationId(correlationId).build());

            messageMappingProcessorActor.tell(commandResponse, getRef());

            final OutboundSignal.Mapped outboundSignal =
                    expectMsgClass(PublishMappedMessage.class).getOutboundSignal().first();
            assertThat(outboundSignal.getSource().getDittoHeaders().getCorrelationId())
                    .contains(correlationId);
        }};
    }


    @Test
    public void testThingNotAccessibleExceptionRetainsTopic() {
        new TestKit(actorSystem) {{
            final ActorRef messageMappingProcessorActor = createMessageMappingProcessorActor(this);

            // WHEN: message mapping processor receives ThingNotAccessibleException with thing-id set from topic path
            final String correlationId = UUID.randomUUID().toString();
            final ThingNotAccessibleException thingNotAccessibleException =
                    ThingNotAccessibleException.newBuilder(KNOWN_THING_ID)
                            .dittoHeaders(HEADERS_WITH_REPLY_INFORMATION.toBuilder()
                                    .correlationId(correlationId)
                                    .putHeader(DittoHeaderDefinition.ENTITY_ID.getKey(), KNOWN_THING_ID)
                                    .build())
                            .build();

            messageMappingProcessorActor.tell(thingNotAccessibleException, getRef());

            final OutboundSignal.Mapped outboundSignal =
                    expectMsgClass(PublishMappedMessage.class).getOutboundSignal().first();

            // THEN: correlation ID is preserved
            assertThat(outboundSignal.getSource().getDittoHeaders().getCorrelationId())
                    .contains(correlationId);

            // THEN: topic-path contains thing ID
            assertThat(outboundSignal.getExternalMessage())
                    .extracting(e -> JsonFactory.newObject(e.getTextPayload().orElse("{}")).getValue("topic"))
                    .isEqualTo(Optional.of(JsonFactory.newValue("my/thing/things/twin/errors")));
        }};
    }

    @Test
    public void testAggregationOfAcknowledgements() {
        new TestKit(actorSystem) {{
            final ActorRef messageMappingProcessorActor = createMessageMappingProcessorActor(this);
            final AcknowledgementRequest signalAck =
                    AcknowledgementRequest.parseAcknowledgementRequest("my-custom-ack-3");
            Set<AcknowledgementRequest> validationSet = new HashSet<>(Collections.singletonList(signalAck));
            validationSet.addAll(CONNECTION.getSources().get(0).getAcknowledgementRequests().map(
                    FilteredAcknowledgementRequest::getIncludes).orElse(Collections.emptySet()));
            final Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "application/json");
            final AuthorizationContext context =
                    AuthorizationModelFactory.newAuthContext(DittoAuthorizationContextType.UNSPECIFIED,
                            AuthorizationModelFactory.newAuthSubject("ditto:ditto"));
            final ModifyAttribute modifyCommand =
                    ModifyAttribute.of(TestConstants.Things.THING_ID, JsonPointer.of("/attribute1"),
                            JsonValue.of("attributeValue"), DittoHeaders.newBuilder().acknowledgementRequest(
                                    signalAck).build());
            final JsonifiableAdaptable adaptable = ProtocolFactory
                    .wrapAsJsonifiableAdaptable(
                            DITTO_PROTOCOL_ADAPTER.toAdaptable(modifyCommand, TopicPath.Channel.TWIN));

            final ExternalMessage message = ExternalMessageFactory.newExternalMessageBuilder(headers)
                    .withTopicPath(adaptable.getTopicPath())
                    .withText(adaptable.toJsonString())
                    .withAuthorizationContext(context)
                    .withHeaderMapping(SOURCE_HEADER_MAPPING)
                    .withSourceAddress(CONNECTION.getSources().get(0).getAddresses().iterator().next())
                    .withSource(CONNECTION.getSources().get(0))
                    .build();

            final TestProbe collectorProbe = TestProbe.apply("collector", actorSystem);
            messageMappingProcessorActor.tell(message, collectorProbe.ref());

            final ModifyAttribute modifyAttribute = expectMsgClass(ModifyAttribute.class);
            assertThat(modifyAttribute.getDittoHeaders().getAcknowledgementRequests()).isEqualTo(validationSet);
        }};
    }

    @Test
    public void testFilteringOfAcknowledgements() {
        new TestKit(actorSystem) {{
            final ActorRef messageMappingProcessorActor = createMessageMappingProcessorActor(this);
            final AcknowledgementRequest signalAck =
                    AcknowledgementRequest.parseAcknowledgementRequest("my-custom-ack-3");
            final Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "application/json");
            headers.put("qos", "0");
            final AuthorizationContext context =
                    AuthorizationModelFactory.newAuthContext(DittoAuthorizationContextType.UNSPECIFIED,
                            AuthorizationModelFactory.newAuthSubject("ditto:ditto"));
            final ModifyAttribute modifyCommand =
                    ModifyAttribute.of(TestConstants.Things.THING_ID, JsonPointer.of("/attribute1"),
                            JsonValue.of("attributeValue"), DittoHeaders.newBuilder().acknowledgementRequest(
                                    signalAck).build());
            final JsonifiableAdaptable adaptable = ProtocolFactory
                    .wrapAsJsonifiableAdaptable(
                            DITTO_PROTOCOL_ADAPTER.toAdaptable(modifyCommand, TopicPath.Channel.TWIN));

            final ExternalMessage message = ExternalMessageFactory.newExternalMessageBuilder(headers)
                    .withTopicPath(adaptable.getTopicPath())
                    .withText(adaptable.toJsonString())
                    .withAuthorizationContext(context)
                    .withHeaderMapping(SOURCE_HEADER_MAPPING)
                    .withSourceAddress(CONNECTION.getSources().get(0).getAddresses().iterator().next())
                    .withSource(CONNECTION.getSources().get(0))
                    .build();

            final TestProbe collectorProbe = TestProbe.apply("collector", actorSystem);
            messageMappingProcessorActor.tell(message, collectorProbe.ref());

            final ModifyAttribute modifyAttribute = expectMsgClass(ModifyAttribute.class);
            assertThat(modifyAttribute.getDittoHeaders().getAcknowledgementRequests()).isEmpty();
        }};
    }

    @Test
    public void testAppendingConnectionIdToResponses() {
        new TestKit(actorSystem) {{
            final ActorRef messageMappingProcessorActor = createMessageMappingProcessorActor(this);

            // Acknowledgement
            final AcknowledgementLabel label = AcknowledgementLabel.of("label");
            final Acknowledgement acknowledgement =
                    Acknowledgement.of(label, KNOWN_THING_ID, HttpStatusCode.BAD_REQUEST,
                            DittoHeaders.empty(), JsonValue.of("payload"));
            messageMappingProcessorActor.tell(toExternalMessage(acknowledgement), getRef());
            final Acknowledgement receivedAck = connectionActorProbe.expectMsgClass(Acknowledgement.class);
            assertThat(receivedAck.getDittoHeaders().get(DittoHeaderDefinition.CONNECTION_ID.getKey()))
                    .isEqualTo(CONNECTION_ID.toString());

            // Acknowledgements
            final Signal<?> acknowledgements = Acknowledgements.of(List.of(acknowledgement), DittoHeaders.empty());
            messageMappingProcessorActor.tell(toExternalMessage(acknowledgements), getRef());
            final Acknowledgements receivedAcks = connectionActorProbe.expectMsgClass(Acknowledgements.class);
            assertThat(receivedAcks.getAcknowledgement(label)
                    .orElseThrow()
                    .getDittoHeaders()
                    .get(DittoHeaderDefinition.CONNECTION_ID.getKey()))
                    .isEqualTo(CONNECTION_ID.toString());

            // Live response
            final Signal<?> liveResponse = DeleteThingResponse.of(KNOWN_THING_ID, DittoHeaders.newBuilder()
                    .channel(TopicPath.Channel.LIVE.getName())
                    .build());
            messageMappingProcessorActor.tell(toExternalMessage(liveResponse), getRef());
            final DeleteThingResponse receivedResponse = connectionActorProbe.expectMsgClass(DeleteThingResponse.class);
            assertThat(receivedResponse.getDittoHeaders().getChannel()).contains(TopicPath.Channel.LIVE.getName());
            assertThat(receivedResponse.getDittoHeaders().get(DittoHeaderDefinition.CONNECTION_ID.getKey()))
                    .isEqualTo(CONNECTION_ID.toString());
        }};
    }

    @Test
    public void forwardsSearchCommandsToConnectionActor() {
        new TestKit(actorSystem) {{
            final ActorRef messageMappingProcessorActor = createMessageMappingProcessorActor(this);
            final Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "application/json");
            final AuthorizationContext context =
                    AuthorizationModelFactory.newAuthContext(AuthorizationModelFactory.newAuthSubject("ditto:ditto"));
            final CancelSubscription searchCommand =
                    CancelSubscription.of("sub-" + UUID.randomUUID(), DittoHeaders.empty());
            final JsonifiableAdaptable adaptable = ProtocolFactory
                    .wrapAsJsonifiableAdaptable(DITTO_PROTOCOL_ADAPTER.toAdaptable(searchCommand));
            final ExternalMessage externalMessage = ExternalMessageFactory.newExternalMessageBuilder(headers)
                    .withTopicPath(adaptable.getTopicPath())
                    .withText(adaptable.toJsonString())
                    .withAuthorizationContext(context)
                    .withHeaderMapping(SOURCE_HEADER_MAPPING)
                    .build();

            messageMappingProcessorActor.tell(externalMessage, getRef());

            final CancelSubscription received = connectionActorProbe.expectMsgClass(CancelSubscription.class);
            assertThat(received.getSubscriptionId()).isEqualTo(searchCommand.getSubscriptionId());
        }};
    }
}
