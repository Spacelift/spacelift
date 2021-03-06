package space.spacelift.framework

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

import akka.actor._
import space.spacelift.mq.proxy._
import javax.inject._

import akka.NotUsed
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.pattern.{ask, pipe}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Flow
import akka.testkit.TestActorRef
import akka.util.Timeout
import space.spacelift.framework.RestfulActorSystem.ServerError
import space.spacelift.mq.proxy.patterns.RpcClient
import space.spacelift.mq.proxy.serializers.Serializers

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._

object RestfulActorSystem {
  case class ServerError(msg: String)
}

@Singleton
class RestfulActorSystem @Inject() (proxiedActorSystem: ProxiedActorSystem) {
  import proxiedActorSystem._

  private val actorMap: scala.collection.concurrent.Map[String, (ActorRef, Actor.Receive)] = new ConcurrentHashMap[String, (ActorRef, Actor.Receive)]().asScala
  private val classMap: scala.collection.concurrent.Map[String, List[String]] = new ConcurrentHashMap[String, List[String]]().asScala

  def loadClassList(root: File, file: File): List[String] = {
    if (!file.isDirectory) {
      if (file.getName.toLowerCase().endsWith(".jar")) {
        try {
          new JarFile(file).entries().asScala.map(_.getName).filter(_.lastIndexOf(".class") > 0).map(n => n.substring(0, n.lastIndexOf(".class")).replace("/", ".")).toList
        } catch {
          case _ => List()
        }
      } else {
        if (file.getName.toLowerCase().endsWith(".class")) {
          try {
            List(file.getAbsolutePath.substring(file.getAbsolutePath.lastIndexOf(root.getAbsolutePath) + root.getAbsolutePath.length + 1, file.getAbsolutePath.lastIndexOf(".class")).replace("/", "."))
          } catch {
            case _ => List()
          }
        } else List()
      }
    } else {
      file.listFiles().toList.flatMap(c => loadClassList(root, c))
    }
  }

  /**
    * Starts the REST server using all actors created through the extension method [[RestfulActorOf.restfulActorOf]].
    *
    * @param system
    */
  def startServer(implicit system: ActorSystem) = {
    val list = this.getClass.getClassLoader.getResources("").asScala.flatMap(p => loadClassList(new File(p.getPath), new File(p.getPath))).toList

    implicit val materializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher

    val routes: Flow[HttpRequest, HttpResponse, NotUsed] = Flow[HttpRequest].map { req =>
      val paths = req.uri.path.toString.split("/")

      paths.length match {
        case 0 => HttpResponse(StatusCodes.NotFound, entity = HttpEntity("Not Found"))
        case 1 => HttpResponse(StatusCodes.NotFound, entity = HttpEntity("Not Found"))
        case _ => {
          val key = paths(1)
          if (actorMap.contains(key)) {
            if (!classMap.contains(key)) {
              classMap.put(key, list.filter(c => try {
                actorMap(key)._2.isDefinedAt(this.getClass.getClassLoader.loadClass(c).newInstance())
              } catch {
                case _ => false
              }))
            }

            implicit val timeout: Timeout = 30 seconds
            val msgKey = paths(2)

            val contentType = (if (req.method.value == "GET") {
              req.headers.find(_.is("accept"))
            } else {
              req.headers.find(_.is("content-type"))
            }).map(_.value).getOrElse("application/json")

            classMap(key).find(_.split("\\.").last.equals(msgKey)) match {
              case Some(className) => Await.result(req.entity.toStrict(30 seconds).flatMap { s =>
                (actorMap(key)._1 ? Delivery(
                  s.data.toArray,
                  MessageProperties(
                    className,
                    contentType
                  )
                )).mapTo[HttpResponse]
              }, 30 seconds)
              case None => HttpResponse(
                StatusCodes.NotFound,
                entity = HttpEntity(
                  ContentType.parse(contentType).right.get,
                  Serializers.contentTypeToSerializer(contentType).toBinary(ServerError("Could not find a matching class"))
                )
              )
            }
          } else {
            HttpResponse(StatusCodes.NotFound, entity = HttpEntity("Not Found"))
          }
        }
      }
    }

    val bindingFuture = Http().bindAndHandle(routes, "localhost", 8080)
  }

  implicit class RestfulActorOf(system: ActorSystem) {
    /**
      * Creates an RPC Client actor and maps it to the service map to create the REST endpoints when [[startServer]] is run.
      *
      * @param props
      * @param name The name of the REST endpoint
      * @return The RPC client instance the REST service envelops
      */
    def restfulActorOf(props: Props, name: String): ActorRef = {
      val client = system.rpcClientActorOf(props, name, new RestfulClient(_))

      actorMap.put(name, (client, TestActorRef(props)(system).underlyingActor.receive))

      client
    }
  }

  object RestfulClient {
    /**
      * Defines a RestfulClient
      *
      * @param client The RPC Client
      * @return Props containing the RestfulClient
      */
    def props(client: ActorRef): Props = Props(new RestfulClient(client))
  }

  /**
    * Implementation of the ProxyClient which allows for control of serialization.
    *
    * @param client RPC Client
    */
  class RestfulClient(client: ActorRef, timeout: Timeout = 30 seconds) extends Actor with ActorLogging {

    import scala.concurrent.ExecutionContext.Implicits.global

    def receive: Actor.Receive = {
      case request: Delivery => {
        log.info("Received request for delivery")
        val future = (client ? RpcClient.Request(request :: Nil, 1))(timeout).mapTo[AnyRef].map {
          case result: RpcClient.Response => {
            log.info("Got result: " + result.toString)
            HttpResponse(
              StatusCodes.OK,
              entity = HttpEntity(
                ContentType.parse(request.properties.contentType).right.get,
                result.deliveries.head.body
              )
            )
          }
          case undelivered: RpcClient.Undelivered => HttpResponse(
            StatusCodes.ServiceUnavailable,
            entity = HttpEntity(
              ContentType.parse(request.properties.contentType).right.get,
              Serializers.contentTypeToSerializer(request.properties.contentType).toBinary(undelivered)
            )
          )
        }

        future.pipeTo(sender)
      }
    }
  }
}
