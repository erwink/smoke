Smoke
======

*Simple, asynchronous HTTP powered by [Akka](http://akka.io)*

A thin DSL for building simple, fast, scalable, asynchronous HTTP services with Scala.

## Getting started

Build a simple application

    import smoke._

    object BasicExampleApp extends Smoke {    
      onRequest {
        case GET(Path("/example")) => reply {
          Thread.sleep(1000)
          Response(Ok, body="It took me a second to build this response.\n")
        }
        case _ => reply(Response(NotFound))
      }
    }

Run it with SBT

    sbt run

By default, Smoke relies on [Netty](http://netty.io) to for asynchronous HTTP communication. Alternatively, you can use [Mongrel2](http://mongrel2.org):

    import smoke.mongrel2.Mongrel2Server

    object BasicExampleApp extends Smoke {    
      override val server = new Mongrel2Server
      ...
    }

Smoke provides a DSL for building HTTP services using a simple request/response pattern, where each response is provided as an Akka Future. Akka provides a powerful toolkit to control the creation and execution of Futures; spend some time with that project's [excellent documentation](http://akka.io/docs) to get a feel for how it works.

By mixing the Smoke trait into your app you get access to the tools necessary to build a robust Akka-based application. That includes to an `ActorSystem`, `Dispatcher`, default timeout and `Config` object. 

    implicit val config = ConfigFactory.load()
    implicit val system = ActorSystem("Smoke", config)
    implicit val dispatcher = system.dispatcher
    implicit val timeout = Timeout(timeoutDuration milliseconds)

Be sure to check out the  [examples](https://github.com/mDialog/smoke/tree/master/src/main/scala/smoke/examples).

## The Responder

The heart of any Smoke app is the responder function. Set it by supplying a function that accepts a `Request` and returns a `Future[Response]` to the `onRequest` method.

    onRequest {
      case GET(Path("/example")) => Future {
        Thread.sleep(1000)
        Response(Ok, body="It took me a second to build this response.\n")
      }
      case _ => reply(Response(NotFound))
    }

Smoke also provides a simple collection of request extractors based on the familiar set found in projects like [Play2 Mini](https://github.com/typesafehub/play2-mini) and [Unfiltered](http://unfiltered.databinder.net/Unfiltered.html). These can be used for pattern matching on `Request` objects.

    case GET(Path(Seg("resources" :: id :: Nil))) => ...
    case POST(Path("/resources")) & Params(p) => ...

To respond, you can use the `reply` method. Supply a function that returns a Response and it will be wrapped in a Future for you.

    case GET(Path("/example")) => reply {
      Thread.sleep(1000)
      Response(Ok, body="It took me a second.\n")
    }

If your request can be responded to quickly and immediately, you can pass a `Response` directly and it will be wrapped in a completed `Future`.

    case _ => reply(Response(NotFound))

Since a reply is just a Future[Response], you can also get one from an actor.

    class Responder extends Actor {
		  def receive = {
        case GET(Path("/example")) => 
          Thread.sleep(1000)
          sender ! Response(Ok, body="It took me a second.\n")
        case _ => sender ! Response(NotFound)
      }
    }

    object ActorExampleApp extends Smoke {
      val actor = system.actorOf(Props[Responder])

      onRequest (actor ? _ mapTo manifest[Response])
    }
    
Even better, you can use the tools provided by Akka to compose your responder function using many Futures:

    class DataSource extends Actor {
      def receive = {
        case _ => sender ! "Some data"
      }
    }

    class ResponseBuilder extends Actor {
      def receive = {
        case s: String => sender ! ("Found: " + s)
      }
    }

    object ChainedActorExampleApp extends Smoke {
      val dataSource = system.actorOf(Props[DataSource])
      val builder = system.actorOf(Props[ResponseBuilder])

      onRequest { request =>
        for {
          data <- dataSource ? request
          response <- builder ? data mapTo manifest[String]
        } yield Response(Ok, body = response)
      }
    }
		
To get a feel for the power of Akka's composable futures, [read the documentation](http://doc.akka.io/docs/akka/snapshot/scala/futures.html).		
		
### Responses

Responses are built using three parameters: a status code object, a `Map` of headers and a request body.

    Response(Ok, Map("Location" -> resource.location), resource.toJson)

## Before/After filters

You can use the `before` and `after` filters to alter either the request prior to sending it to the responder or the response after it's been returned.

    after { resp =>
      val headers = resp.headers -> ("Server" -> "ExampleApp/0.0.1")
      Response(resp.status, headers, resp.body)
    }

## Error handling

If your `Future[Response]` contains an exception, you can catch it and return an alternate response using the `onError` method. Any uncaught exceptions will return a 500.

	onRequest {
	  case _ => fail(NotFoundException)
	}

	onError {
	  case NotFoundException => Response(NotFound)
	}
	
This is especially useful when using a responder function composed from several Futures.

## Basically,

    def application = before andThen onRequest andThen { f => 
      f recover(onError) map after 
    }

*(paraphrased)*

## Graceful shutdown

Smoke will shutdown the server and `ActorSystem` when the process receives a `TERM` signal, from Ctrl-C or `kill <pid>` for instance. You can attach shutdown hooks both before and after this shutdown occurs.

    beforeShutdown {
      println("Getting ready to shutdown")
    }

    afterShutdown {
      println("No longer responding to requests.")
    }

## Configuration

There are a few of configuration options. Like Akka, Smoke uses [Typesafe Config Library](https://github.com/typesafehub/config). You can override any of the default configuration options by adding an `application.conf` file to your project.

    smoke {
      timeout = 2s

      # By default, Smoke uses Netty
      netty {
        port = 7771
      }

      # For Mongrel2 users
      mongrel2 {
        recvAddress = "ipc:///tmp/SmokeMongrel2Request"
        sendAddress = "ipc:///tmp/SmokeMongrel2Response"
      } 
    } 

## Try it out

Clone the repository, run one of the sample apps:

    sbt run

Make requests:

    curl -i http://localhost:7771/example

## TODO

  - Testkit
  - SSL support (in the meantime, we use [stunnel](http://www.stunnel.org/))

## License

This project is released under the Apache License v2, for more details see the 'LICENSE' file.

## Contributing

Fork the projects, add tests if possible and send a pull request.

Suggestion areas of contribution: more Request extractors, better documentation, additional server backends, DSL improvements.

## Contributors

Chris Dinn

**©2012 mDialog Corp. All rights reserved.**
