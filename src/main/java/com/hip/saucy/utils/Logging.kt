package com.hip.saucy.utils

import org.slf4j.Logger
import org.slf4j.LoggerFactory

internal fun Any.log(): Logger {
   return LoggerFactory.getLogger(this::class.java)
}
