/*
 * Copyright 2026 juren233
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.juren233.hyperlyricsenhanced.online.model

import java.io.IOException

/**
 * Thrown by a [SearchSource] when it could not get an answer (network or HTTP error, an error or
 * rate-limit code in the response, an undecodable body). An empty result means the service
 * answered and has nothing; only that is remembered as a miss.
 */
class OnlineSourceRequestException(message: String, cause: Throwable? = null) : IOException(message, cause)
