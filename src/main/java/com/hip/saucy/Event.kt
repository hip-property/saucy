/*-
 * =========================================================BeginLicense
 * Saucy
 * .
 * Copyright (C) 2018 HiP Property
 * .
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ===========================================================EndLicense
 */
package com.hip.saucy

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * A marker interface for things that are sent through
 * the event router.
 *
 * The router accepts any message (provided it implements
 * this marker interface).  However, messages that are not
 * Event's are wrapped.
 *
 * Therefore, it's reccomended for clarity that classes
 * implement either EventPayload or Event, not Dispatchable
 */
interface Dispatchable

/**
 * A marker interface indicating that this is the payload of
 * an event.  When routed through the event router, this
 * object will be wrapped in a WrapperEvent<T>
 */
interface EventPayload : Dispatchable

/**
 * A message that indicates a specific unit of work should be performed,
 * with a specific outcome.
 *
 * Use CommandEvents in systems that are purely event-sourced.
 * CommandEvents still inherit all traditional Event behaviour,
 * ie., are replayed when the service is restarted.
 *
 *
 */
interface CommandEvent<TFailureEvent, TSuccessEvent> : Dispatchable

/**
 * An event, with a payload.
 *
 * TODO : Expand the header content with additional
 * information, like
 */
interface Event<out T : Any> : Dispatchable {
   val header: EventHeader
   val payload: T

   val simpleName: String
      get() = this.payload.javaClass.simpleName

}

class EventIdCounter {
   private val ids = mutableMapOf<Class<out Any>, AtomicInteger>()
   fun nextIdFor(type: Class<out Any>): Int {
      return ids.getOrPut(type, { AtomicInteger(0) }).incrementAndGet()
   }
}

data class EventEnvelope<out T : Any>(
   override val header: EventHeader,
   override val payload: T
) : Event<T>

// TODO : Expand this with other metadata, such as user information, etc
data class EventHeader(val timestamp: Instant = Instant.now(),
                       val eventStreamName: String,
                       val eventStreamId: Int,
                       val payloadType: String
)

class EventFactory(private val eventIdCounter: EventIdCounter = EventIdCounter()) {
   fun <T : Any> eventOf(data: T): EventEnvelope<T> {
      return EventEnvelope(
         header = EventHeader(
            eventStreamName = data::class.java.simpleName,
            eventStreamId = eventIdCounter.nextIdFor(data::class.java),
            payloadType = data::class.java.name
         ),
         payload = data)
   }
}
