package com.hip.saucy

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.Module
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.hip.saucy.utils.log
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.publisher.toFlux
import javax.annotation.concurrent.NotThreadSafe

/**
 * An EventIndex, as returned from an EventStore
 * EventStores may use any numbering strategy, including
 * non-sequential numbers provided they follow the following rules:
 *
 *  - Must be non-negative
 *  - Must start at 0
 *  - Must increment
 */
typealias EventIndex = Long

interface EventStore {
   fun <T : Any> store(event: Event<T>): Mono<Event<T>>
   fun replay(): Flux<Event<Any>> = replay(0L)
   fun replay(index: EventIndex): Flux<Event<Any>>

   @Suppress("UNCHECKED_CAST")
   fun <T : Dispatchable> replay(messageType: Class<T>, since: EventIndex = 0L): Flux<T> {
      return replay(since)
         .filter { it.payload.javaClass == messageType }
         .map { it.payload as T }
   }

   val currentIndex: EventIndex
}

@NotThreadSafe
/**
 * Event store which just returns the provided
 * event.
 *
 * Consumers must be very careful using this store
 * as if the events are not truly immutable
 * (ie., they contain aggregates / domain classes
 * which mutate), then the events will not
 * replay consistently.
 *
 * Intended only for use in performance tests,
 * where the desire is to measure business logic
 * without the overhead of serialization.
 */
class PassThroughEventStore : EventStore {
   private val events = mutableListOf<Event<Any>>()
   override fun <T : Any> store(event: Event<T>): Mono<Event<T>> {
      events.add(event)
      return Mono.just(event)
   }

   override fun replay(index: EventIndex): Flux<Event<Any>> {
      return events
         .subList(index.toInt(), events.size)
         .toFlux()
   }

   override val currentIndex: EventIndex
      get() = events.size.toLong()
}

@NotThreadSafe
class MemoryEventStore(private val mapper: ObjectMapper = defaultMapper()) : EventStore {

   companion object {
      fun mapperWithModules(vararg modules: Module): ObjectMapper =
         jacksonObjectMapper()
            .findAndRegisterModules()
            .registerModules(modules.toList())
            .enableDefaultTypingAsProperty(ObjectMapper.DefaultTyping.NON_FINAL, "@type")
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

      fun defaultMapper() = mapperWithModules(JavaTimeModule())
   }

   val events = mutableListOf<Pair<Class<out Event<*>>, String>>()
   override val currentIndex: EventIndex
      get() = events.size.toLong()

   override fun <T : Any> store(event: Event<T>): Mono<Event<T>> {
      val json = mapper.writeValueAsString(event)

      // Note: we store the json, rather than the cloned event.
      // This is because the event may contain (mutable) domain objects, which
      // are passed around and mutated, causing our store to become
      // contaminated
      events.add(event::class.java to json)
      val clonedEvent = mapper.readValue(json, event::class.java)
      log().debug("Event ${event.javaClass.name} written to event store")
      return Mono.just(clonedEvent)
   }

   override fun replay(index: EventIndex): Flux<Event<Any>> {
      return events.subList(index.toInt(), events.size)
         .map { (clazz, json) ->
            mapper.readValue(json, clazz) as Event<Any>
         }
         .toFlux()
   }

}
