package com.coralogix.zio.k8s.client.config

import com.coralogix.zio.k8s.client.model.K8sCluster
import sttp.client3.httpclient.zio._
import sttp.client3.logging.slf4j.Slf4jLoggingBackend
import zio._

import java.net.http.HttpClient

/** HTTP client implementation based on the httpclient-zio backend
  */
package object httpclient {

  /** An [[SttpClient]] layer configured with the proper SSL context based on the provided
    * [[K8sClusterConfig]] using the httpclient-backend-zio backend.
    */
  val k8sSttpClient: ZLayer[K8sClusterConfig, Throwable, SttpClient] =
    ZLayer.scoped {
      for {
        config                      <- ZIO.service[K8sClusterConfig]
        disableHostnameVerification <- ZIO.succeed(getHostnameVerificationDisabled(config))
        _                           <- ZIO
                                         .attempt {
                                           java.lang.System.setProperty(
                                             "jdk.internal.httpclient.disableHostnameVerification",
                                             "true"
                                           )
                                         }
                                         .when(disableHostnameVerification)

        sslContext <- SSL(config.client.serverCertificate, config.authentication)
        client     <- ZIO.scoped(
                        ZIO
                          .acquireRelease(
                            ZIO.attempt(
                              HttpClientZioBackend.usingClient(
                                HttpClient
                                  .newBuilder()
                                  .followRedirects(HttpClient.Redirect.NORMAL)
                                  .sslContext(sslContext)
                                  .build()
                              )
                            )
                          )(_.close().ignore)
                          .map { backend =>
                            Slf4jLoggingBackend(
                              backend,
                              logRequestBody = config.client.debug,
                              logResponseBody = config.client.debug
                            )
                          }
                      )
      } yield client
    }

  /** Layer producing a [[K8sCluster]] and an [[SttpClient]] module that can be directly used to
    * initialize specific Kubernetes client modules, using the [[defaultConfigChain]].
    */
  val k8sDefault: ZLayer[Any, Throwable, K8sCluster with SttpClient] =
    defaultConfigChain >>> (k8sCluster ++ k8sSttpClient)

  def getHostnameVerificationDisabled(config: K8sClusterConfig) =
    config.client.serverCertificate match {
      case K8sServerCertificate.Insecure                               => true
      case K8sServerCertificate.Secure(_, disableHostnameVerification) =>
        disableHostnameVerification
    }
}
