/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.client.admin;

import javax.ws.rs.ClientErrorException;
import javax.ws.rs.ServerErrorException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;

import org.apache.pulsar.common.policies.data.ErrorData;


@SuppressWarnings("serial")
public class PulsarAdminException extends Exception {
    private static final int DEFAULT_STATUS_CODE = 500;

    private final String httpError;
    private final int statusCode;

    private static String getReasonFromServer(WebApplicationException e) {
        if (MediaType.APPLICATION_JSON.equals(e.getResponse().getHeaderString("Content-Type"))) {
            try {
                return e.getResponse().readEntity(ErrorData.class).reason;
            } catch (Exception ex) {
                // could not parse output to ErrorData class
                return e.getMessage();
            }
        }
        return e.getMessage();
    }

    public PulsarAdminException(ClientErrorException e) {
        super(getReasonFromServer(e), e);
        this.httpError = e.getMessage();
        this.statusCode = e.getResponse().getStatus();
    }

    public PulsarAdminException(ClientErrorException e, String message) {
        super(message, e);
        this.httpError = e.getMessage();
        this.statusCode = e.getResponse().getStatus();
    }

    public PulsarAdminException(ServerErrorException e) {
        super(getReasonFromServer(e), e);
        this.httpError = e.getMessage();
        this.statusCode = e.getResponse().getStatus();
    }

    public PulsarAdminException(ServerErrorException e, String message) {
        super(message, e);
        this.httpError = e.getMessage();
        this.statusCode = e.getResponse().getStatus();
    }

    public PulsarAdminException(Throwable t) {
        super(t);
        httpError = null;
        statusCode = DEFAULT_STATUS_CODE;
    }

    public PulsarAdminException(String message, Throwable t) {
        super(message, t);
        httpError = null;
        statusCode = DEFAULT_STATUS_CODE;
    }

    public PulsarAdminException(String message) {
        super(message);
        httpError = null;
        statusCode = DEFAULT_STATUS_CODE;
    }

    public String getHttpError() {
        return httpError;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public static class NotAuthorizedException extends PulsarAdminException {
        public NotAuthorizedException(ClientErrorException e) {
            super(e);
        }
    }

    public static class NotFoundException extends PulsarAdminException {
        public NotFoundException(ClientErrorException e) {
            super(e);
        }
    }

    public static class NotAllowedException extends PulsarAdminException {
        public NotAllowedException(ClientErrorException e) { super(e); }
    }

    public static class ConflictException extends PulsarAdminException {
        public ConflictException(ClientErrorException e) {
            super(e);
        }
    }

    public static class PreconditionFailedException extends PulsarAdminException {
        public PreconditionFailedException(ClientErrorException e) {
            super(e);
        }
    }

    public static class ServerSideErrorException extends PulsarAdminException {
        public ServerSideErrorException(ServerErrorException e) {
            super(e, "Some error occourred on the server");
        }
    }

    public static class HttpErrorException extends PulsarAdminException {
        public HttpErrorException(Exception e) {
            super(e);
        }

        public HttpErrorException(Throwable t) {
            super(t);
        }
    }

    public static class ConnectException extends PulsarAdminException {
        public ConnectException(Throwable t) {
            super(t);
        }

        public ConnectException(String message, Throwable t) {
            super(message, t);
        }
    }

    public static class GettingAuthenticationDataException extends PulsarAdminException {
        public GettingAuthenticationDataException(Throwable t) {
            super(t);
        }

        public GettingAuthenticationDataException(String msg) {
            super(msg);
        }
    }
}
