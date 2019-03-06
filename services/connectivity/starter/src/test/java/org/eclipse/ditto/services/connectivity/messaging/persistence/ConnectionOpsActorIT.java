/*
 * Copyright (c) 2017-2018 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.connectivity.messaging.persistence;

import java.time.Duration;
import java.util.Collections;

import org.eclipse.ditto.model.base.auth.AuthorizationContext;
import org.eclipse.ditto.model.base.auth.AuthorizationSubject;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.connectivity.Connection;
import org.eclipse.ditto.model.connectivity.ConnectionType;
import org.eclipse.ditto.model.connectivity.ConnectivityModelFactory;
import org.eclipse.ditto.model.connectivity.ConnectivityStatus;
import org.eclipse.ditto.model.connectivity.Source;
import org.eclipse.ditto.services.connectivity.messaging.ClientActorPropsFactory;
import org.eclipse.ditto.services.connectivity.messaging.ConnectionSupervisorActor;
import org.eclipse.ditto.services.connectivity.messaging.DefaultClientActorPropsFactory;
import org.eclipse.ditto.services.utils.persistence.mongo.ops.eventsource.OpsActorTestCases;
import org.eclipse.ditto.signals.commands.connectivity.ConnectivityCommand;
import org.eclipse.ditto.signals.commands.connectivity.ConnectivityCommandInterceptor;
import org.eclipse.ditto.signals.commands.connectivity.exceptions.ConnectionNotAccessibleException;
import org.eclipse.ditto.signals.commands.connectivity.modify.CreateConnection;
import org.eclipse.ditto.signals.commands.connectivity.modify.CreateConnectionResponse;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnection;
import org.eclipse.ditto.signals.commands.connectivity.query.RetrieveConnectionResponse;
import org.eclipse.ditto.utils.jsr305.annotations.AllValuesAreNonnullByDefault;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import com.typesafe.config.Config;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.testkit.TestProbe;

/**
 * Tests {@link ConnectionOpsActor}.
 */
@AllValuesAreNonnullByDefault
@RunWith(Parameterized.class)
public final class ConnectionOpsActorIT extends OpsActorTestCases {

    @Parameterized.Parameters(name = "{0}")
    public static Iterable<TestSetting> data() {
        return Collections.singleton(TestSetting.ENTITIES_WITHOUT_NAMESPACE);
    }

    public ConnectionOpsActorIT(final OpsActorTestCases.TestSetting testSetting) {
        super(testSetting);
    }

    @Override
    protected boolean idsStartWithNamespace() {
        return false;
    }

    @Override
    protected String getServiceName() {
        return "connectivity";
    }

    @Override
    protected String getResourceType() {
        return ConnectivityCommand.RESOURCE_TYPE;
    }

    @Override
    protected Object getCreateEntityCommand(final String id) {
        final AuthorizationContext authorizationContext =
                AuthorizationContext.newInstance(AuthorizationSubject.newInstance("subject"));
        final Source source =
                ConnectivityModelFactory.newSource(authorizationContext, "address");
        final Connection connection =
                ConnectivityModelFactory.newConnectionBuilder(id, ConnectionType.AMQP_091, ConnectivityStatus.CLOSED,
                        "amqp://user:pass@localhost:5671")
                        .sources(Collections.singletonList(source))
                        .build();
        return CreateConnection.of(connection, DittoHeaders.empty());
    }

    @Override
    protected Class<?> getCreateEntityResponseClass() {
        return CreateConnectionResponse.class;
    }

    @Override
    protected Object getRetrieveEntityCommand(final String id) {
        return RetrieveConnection.of(id, DittoHeaders.empty());
    }

    @Override
    protected Class<?> getRetrieveEntityResponseClass() {
        return RetrieveConnectionResponse.class;
    }

    @Override
    protected Class<?> getEntityNotAccessibleClass() {
        return ConnectionNotAccessibleException.class;
    }

    @Override
    protected ActorRef startActorUnderTest(final ActorSystem actorSystem, final ActorRef pubSubMediator,
            final Config config) {

        final Props opsActorProps = ConnectionOpsActor.props(pubSubMediator, config);
        return actorSystem.actorOf(opsActorProps, ConnectionOpsActor.ACTOR_NAME);
    }

    @Override
    protected ActorRef startEntityActor(final ActorSystem system, final ActorRef pubSubMediator, final String id) {
        // essentially never restart
        final Duration minBackOff = Duration.ofSeconds(36000);
        final Duration maxBackOff = Duration.ofSeconds(36000);
        final double randomFactor = 0.2;

        final TestProbe conciergeForwarderProbe = new TestProbe(system, "conciergeForwarder");
        final ConnectivityCommandInterceptor dummyInterceptor = (command) -> {};
        final ClientActorPropsFactory entityActorFactory = DefaultClientActorPropsFactory.getInstance();
        final Props props = ConnectionSupervisorActor.props(minBackOff, maxBackOff, randomFactor, pubSubMediator,
                conciergeForwarderProbe.ref(), entityActorFactory, dummyInterceptor);

        return system.actorOf(props, id);
    }

}
