import besom.*
import besom.api.random.*
import besom.api.kubernetes.core.v1.Namespace
import besom.api.kubernetes.apps.v1.Deployment
import besom.api.kubernetes.core.v1.NamespaceArgs
import besom.api.kubernetes.apps.v1.DeploymentArgs
import besom.api.kubernetes.meta.v1.inputs.ObjectMetaArgs
import besom.api.kubernetes.apps.v1.inputs.DeploymentSpecArgs
import besom.api.kubernetes.meta.v1.inputs.LabelSelectorArgs
import besom.api.kubernetes.core.v1.inputs.PodTemplateSpecArgs
import besom.api.kubernetes.core.v1.inputs.PodSpecArgs
import besom.api.kubernetes.core.v1.inputs.ContainerArgs
import besom.api.kubernetes.core.v1.inputs.ContainerPortArgs
import besom.api.kubernetes.core.v1.inputs.EnvVarArgs
import besom.api.kubernetes.core.v1.Service
import besom.api.kubernetes.core.v1.ServiceArgs
import besom.api.kubernetes.core.v1.inputs.ServiceSpecArgs
import besom.api.kubernetes.core.v1.inputs.ServicePortArgs
import besom.api.kubernetes.networking.v1.inputs.HttpIngressPathArgs
import besom.api.kubernetes.networking.v1.inputs.IngressServiceBackendArgs
import besom.api.kubernetes.networking.v1.inputs.ServiceBackendPortArgs
import besom.api.kubernetes.networking.v1.Ingress
import besom.api.kubernetes.networking.v1.IngressArgs
import besom.api.kubernetes.networking.v1.inputs.IngressSpecArgs
import besom.api.kubernetes.networking.v1.inputs.IngressRuleArgs
import besom.api.kubernetes.networking.v1.inputs.HttpIngressRuleValueArgs
import besom.api.kubernetes.networking.v1.inputs.IngressBackendArgs

@main def main = Pulumi.run {
  val ns           = Namespace("registry-viewer", NamespaceArgs())
  val randomSecret = RandomPassword("secret", RandomPasswordArgs(length = 24))
  val dockerUrl = config
    .getString("dockerUrl")
    .getOrFail(new Exception("dockerUrl is required"))
  val dockerUser = config
    .getString("dockerUser")
    .getOrFail(new Exception("dockerUser is required"))
  val dockerPassword = config
    .getString("dockerPassword")
    .getOrFail(new Exception("dockerPassword is required"))

  val deploymentOutput = for {
    n    <- ns
    rs   <- randomSecret
    url  <- dockerUrl
    user <- dockerUser
    pass <- dockerPassword
    dep  <- deployment(n, url, user, pass, rs)
  } yield dep

  val svc = Service(
    "registry-viewer-service",
    ServiceArgs(
      metadata = ObjectMetaArgs(namespace = ns.metadata.name),
      spec = ServiceSpecArgs(
        selector = Map("app" -> "registry-viewer"),
        ports = List(ServicePortArgs(port = 8080, protocol = "TCP")),
      ),
    ),
  )

  val ing = for {
    serviceName <- svc.metadata.name
  } yield Ingress(
    "registry-viewer-ingress",
    IngressArgs(
      metadata = ObjectMetaArgs(namespace = ns.metadata.name),
      spec = IngressSpecArgs(
        rules = List(
          IngressRuleArgs(
            host = "regi.k",
            http = HttpIngressRuleValueArgs(
              paths = List(
                HttpIngressPathArgs(
                  path = "/",
                  pathType = "Prefix",
                  backend = IngressBackendArgs(
                    service = IngressServiceBackendArgs(
                      name = serviceName.get,
                      port = ServiceBackendPortArgs(number = 8080),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
        ingressClassName = "nginx",
      ),
    ),
  )

  Stack.exports(
    ns = ns,
    deploymentOut = deploymentOutput,
    service = svc,
    ingress = ing,
  )
}

def deployment(
    ns: Namespace,
    dockerUrl: String,
    dockerUser: String,
    dockerPassword: String,
    randomSecret: RandomPassword,
)(using Context) = Deployment(
  "registry-viewer",
  DeploymentArgs(
    metadata = ObjectMetaArgs(namespace = ns.metadata.name),
    spec = DeploymentSpecArgs(
      replicas = 1,
      selector =
        LabelSelectorArgs(matchLabels = Map("app" -> "registry-viewer")),
      template = PodTemplateSpecArgs(
        metadata = ObjectMetaArgs(labels = Map("app" -> "registry-viewer")),
        spec = PodSpecArgs(
          containers = List(
            ContainerArgs(
              name = "registry-viewer",
              image = "klausmeyer/docker-registry-browser:latest",
              ports = List(ContainerPortArgs(containerPort = 8080)),
              env = List(
                EnvVarArgs(
                  name = "SECRET_KEY_BASE",
                  value = randomSecret.result,
                ),
                EnvVarArgs(
                  name = "DOCKER_REGISTRY_URL",
                  value = dockerUrl,
                ),
                EnvVarArgs(
                  name = "BASIC_AUTH_USER",
                  value = dockerUser,
                ),
                EnvVarArgs(
                  name = "BASIC_AUTH_PASSWORD",
                  value = dockerPassword,
                ),
              ),
            ),
          ),
        ),
      ),
    ),
  ),
)
