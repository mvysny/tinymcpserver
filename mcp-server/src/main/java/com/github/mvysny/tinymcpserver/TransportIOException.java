/*
 * Copyright 2026 Martin Vysny
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.github.mvysny.tinymcpserver;

import java.io.IOException;

/**
 * A transport's {@link IOException}, unchecked: the socket or stream is
 * unusable, so log and abandon rather than try to respond
 * (D_three_error_layers). Handler
 * code must not throw it — a handler-side I/O failure is an
 * {@link MCPServerException}.
 */
public class TransportIOException extends RuntimeException {

    TransportIOException(IOException cause) {
        super(cause);
    }

    TransportIOException(String message, IOException cause) {
        super(message, cause);
    }
}
