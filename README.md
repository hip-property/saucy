# Saucy
This is an experimental event sourcing framework we created at HiP.

It's not currently under active development, and not currently in use, though we may revisit at a later stage.

It supports message invocation from a router onto Spring annotated methods
Here's an example in use:
```kotlin
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
```
