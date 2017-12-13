/*
 * Copyright (c) 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial contribution
 */
package org.eclipse.ditto.services.amqpbridge.messaging;

import javax.jms.Connection;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.naming.NamingException;

import org.eclipse.ditto.model.amqpbridge.AmqpConnection;

/**
 * Creates a new {@link javax.jms.Connection}.
 */
public interface JmsConnectionFactory {

    /**
     * Creates a new {@code Connection}.
     *
     * @param amqpConnection the amqp connection to use for the returned JMS Connection.
     * @param exceptionListener the ExceptionListener to configure for the returned JMS Connection.
     * @return the JMS Connection.
     * @throws javax.jms.JMSException if the context could not be created.
     * @throws javax.naming.NamingException if the identifier of {@code connection} could not be found in the Context.
     */
    Connection createConnection(final AmqpConnection amqpConnection, ExceptionListener exceptionListener)
            throws JMSException, NamingException;

}
