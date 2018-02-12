package com.hip.saucy

import com.winterbe.expekt.expect
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import reactor.core.publisher.Flux

interface RequestMessage : Dispatchable
data class Message(val value: String) : RequestMessage
data class Response(val count: Int) : Dispatchable
object MultiTrigger : Dispatchable
class EventRouterTest : Spek({

   class Handler {
      var handleMessageCount: Int = 0
      var handleResponseCount: Int = 0
      var handleAnyCount: Int = 0
      var handleRequestCount: Int = 0
      @MessageHandler
      fun handle(message: Message) = Response(++handleMessageCount)

      @MessageHandler
      fun responseHandler(response: Response) {
         handleResponseCount++
      }

      @MessageHandler
      fun handleAny(message: Any) {
         handleAnyCount++
      }

      @MessageHandler
      fun handleRequests(message: RequestMessage) {
         handleRequestCount++
      }

      @MessageHandler
      fun publishMultipleResponses(message: MultiTrigger): Flux<Message> {
         return Flux.fromIterable(listOf(
            Message("Hello"),
            Message("dear"),
            Message("world")))
      }
   }

   describe("Invoking methods annotated with @MessageHandler") {
      lateinit var router: EventRouter
      lateinit var handler: Handler
      beforeEachTest {
         handler = Handler()
         router = EventRouter.withMembers(handler).build()
      }

      it("should invoke method if types match") {
         expect(handler.handleMessageCount).to.equal(0)
         router.dispatch(Message(""))
         expect(handler.handleMessageCount).to.equal(1)
      }
      it("should return a Flux containing the result") {
         val response = router.dispatch(Message("")).blockFirst() as Response
         expect(response.count).to.equal(1)
         expect(handler.handleMessageCount).to.equal(1)
      }

      it("should invoke message handlers that accept supertypes of the dispatched type") {
         expect(handler.handleAnyCount).to.equal(0)
         router.dispatch(Message(""))

         // expect matched, as Message implements RequestMessage
         expect(handler.handleRequestCount).to.equal(1)

         // expect 2 because the response returned should also have been handled.
         // However, handleRequests() accepts RequestMessage, which Response does not
         // implement, so only the HandleAny method should've been invoked.
         expect(handler.handleAnyCount).to.equal(2)
      }

      describe("invoking messages from responses") {
         it("should invoke message handlers that consume the response") {
            expect(handler.handleResponseCount).to.equal(0)
            router.dispatch(Message(""))
            expect(handler.handleResponseCount).to.equal(1)
         }
         it("should invoke message handlers from a Flux of responses") {
            expect(handler.handleMessageCount).to.equal(0)
            // Multitrigger results in a stream of Message being published,
            // which should result in multiple calls to handleMessage()
            router.dispatch(MultiTrigger)

            expect(handler.handleMessageCount).to.equal(3)
         }
      }

      describe("replaying events") {
         it("should generate the same state when replaying") {
            router.dispatch(MultiTrigger)

            val secondHandler = Handler()
            val secondRouter = EventRouter
               .withMembers(secondHandler)
               .withEventStore(router.eventStore)
               .replayOnStartup()
               .build()

            expect(handler.handleAnyCount).to.equal(secondHandler.handleAnyCount)
            expect(handler.handleMessageCount).to.equal(secondHandler.handleMessageCount)
            expect(handler.handleRequestCount).to.equal(secondHandler.handleRequestCount)
            expect(handler.handleResponseCount).to.equal(secondHandler.handleResponseCount)
         }
      }


   }
})
