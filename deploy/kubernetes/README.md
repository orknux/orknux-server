# Running Orknux on Kubernetes

[`orknux.yaml`](orknux.yaml) in this directory is a whole Orknux, from the
images published on Docker Hub. It is the same deployment
[`deploy/compose.yaml`](../compose.yaml) describes — the same five services, the
same images, the same arrangement — written as Kubernetes objects.

```
kubectl create namespace orknux
kubectl -n orknux create secret generic orknux-secret-key \
  --from-literal=secret-key="$(openssl rand -base64 32)"
kubectl apply -f orknux.yaml
```

Then, to look at it:

```
kubectl -n orknux port-forward svc/orknux-ui 8080:8080
```

and open **http://localhost:8080**, signing in as `alice` / `password`.

The first start takes a few minutes: Temporal applies its own schema, and the
server runs its way through a hundred and twenty-odd Flyway migrations before it
answers anything. `kubectl -n orknux logs -f deploy/orknux-server` is where it
says so, and the server's startup probe allows ten minutes before it gives up.

**Admin -> Doctor is how you check it landed properly**, and on a fresh
deployment from this manifest it reports the secret key set and the right length,
the schema applied with nothing failed, authentication against the directory, and
attachments writable at `/home/orknux/attachments` — that last one being the
`fsGroup` in the manifest doing its job. One `WARN`, for **Allowed origins**, is
correct and expected here: it is empty because the browser only ever talks to
`orknux-ui`, and filling it in is only right where the interface is served from
somewhere else.

**[`deploy/README.md`](../README.md) is the other half of this page and is not
repeated here.** What each service is for, why LDAP and Temporal are not
optional, the trade between Postgres and SQLite, how to point the server at a
directory you already run or at an OIDC provider instead, signing in without a
directory at all, and the full table of settings — all of that is true whatever
runs the containers, and it is written down there. This page is what is
different because it is Kubernetes.

## The secret key, before anything else

`ORKNUX_SECRET_KEY` is the one setting with no default. In compose it is a
variable the file refuses to start without; here it is a Secret the manifest
does not contain, and the server's pods stay in `CreateContainerConfigError`
until you create it. Same guard, spelled the way Kubernetes spells it.

It encrypts every credential the server is trusted with — model provider keys,
Slack tokens, MCP secrets — so a copy of the database on its own is not enough
to use them. It has to be 32 bytes, base64 encoded.

**Changing it or losing it strands every secret already stored.** Nothing
decrypts them afterwards; they have to be entered again, by hand, one at a time.
So back it up somewhere other than the database it protects, and treat it as
permanent from the moment you save the first credential:

```
kubectl -n orknux get secret orknux-secret-key -o jsonpath='{.data.secret-key}' | base64 -d
```

A `Secret` is base64, not encryption. Anyone who can read the object can read
the key, and so can anyone who can read etcd. If you already run External
Secrets, Sealed Secrets, or a CSI driver against a vault, this is the one value
worth moving there first — and the manifest is arranged so that moving it
changes nothing but the name in a `secretKeyRef`.

`orknux-secret-key` is deliberately a different object from the `orknux` Secret
that carries the database and directory passwords. Those two have working
defaults and are in the file; this one never is.

## What is different from compose

Most of it is a rename. These are the places where the answer is not the same.

**The server waits in an init container rather than crash-looping.** Compose has
`depends_on` with health conditions; Kubernetes has no such thing. Left alone,
the server would come up before Temporal, refuse to start — which it does on
purpose, rather than accept work it has no way to run — and recover through
`CrashLoopBackOff`, which reads like a broken installation and backs off up to
five minutes on a first start. A `busybox` init container waits for Postgres,
LDAP and Temporal instead, so a first start reads as `Init:0/1` against
something named.

**The probes ask a real question.** The compose health check is a bash
`/dev/tcp` connect because the server image carries neither curl nor wget, so
there is nothing in it that can make an HTTP request. kubelet makes the request
itself, from outside the container, so the probes here read
`/api/auth/method` — the same endpoint `scripts/verify-image.sh` waits on, open
by design and needing no credential. Actuator's health endpoint is deliberately
not exposed and is not what to point these at.

**There is a startup probe, and it is doing the work.** A first start is a
hundred and twenty-odd migrations long. The liveness probe does not run until
the startup probe has succeeded once, which is what stops a slow first start
from being read as a hung server and restarted into another slow first start.

**The memory limit is what sizes the heap.** The image sets
`-XX:MaxRAMPercentage=75` and a container sees its cgroup limit rather than the
node, so `limits.memory: 2Gi` on the server is a heap of about 1.5Gi. Removing
the limit does not give the JVM more room — it gives it the node's memory to
size itself against, on a pod the kubelet will evict first. Raise the limit to
raise the heap.

**Everything that owns a claim is `strategy: Recreate`.** The claims are
`ReadWriteOnce`. A rolling update would ask for a second pod that cannot mount a
volume the first still holds, and wait for it forever. Recreate stops the old
pod first, which costs a few seconds of downtime on every deploy and is the only
thing that works.

**Postgres is a StatefulSet.** One replica, and the point is not identity: a
StatefulSet with one replica stops the old pod before starting the new one, and
its claim is not deleted when the object is. A Deployment can be made to behave
the first way and cannot be made to behave the second.

**Nothing waits for the server before the interface starts.** nginx resolves
`ORKNUX_SERVER_URL` once, when it starts — which is why compose needs
`depends_on` there. A ClusterIP exists from the moment the Service does, whether
or not a pod is behind it yet, and does not change for the life of the Service,
so the interface can start first and a replica started next month agrees with
one started today about where the server is.

**Do not point the interface at a headless Service.** It would hand nginx a pod
IP, resolved once and cached past that pod's death, and the symptom is an
interface that served fine for a week and now 502s everything under `/api` while
the server is plainly healthy.

**Temporal's health check cannot use the name the compose file uses.** Its
readiness probe asks `$(hostname -i):7233`, which looks like a workaround and is
the only address of the three that works.

The image binds to its own container address rather than to `0.0.0.0` — it
defaults `BIND_ON_IP` to `hostname -i` — so 7233 is open on the pod IP and on
nothing else, and `localhost:7233` is refused. That much is merely awkward.

`temporal:7233`, which is what `deploy/compose.yaml` health-checks, is the trap.
Under compose the name resolves to the container, which is exactly where
Temporal is listening. Here it resolves to the Service's ClusterIP, and a
ClusterIP routes only to endpoints that are *ready* — so the probe cannot pass
until the pod is ready and the pod cannot become ready until the probe passes.
Nothing breaks, nothing restarts and nothing is logged: Temporal sits `Running`
and `0/1` for as long as you leave it, writing perfectly healthy logs, and the
server waits at `Init:0/1` behind it for ever. It is the quietest way this
manifest can fail, which is why the reasoning sits in the file beside the probe
rather than only here.

**Every pod sets `enableServiceLinks: false`, and the directory does not start
without it.** This is the one thing on this page that was found by running the
manifest rather than by writing it, and it is worth the space because nothing
about it is visible in either half.

By default kubelet injects a Docker-link-style variable into every container for
every Service in the namespace. A Service named `ldap` becomes `LDAP_PORT`,
`LDAP_SERVICE_HOST` and half a dozen more, in every pod, whether or not anything
asked for them — and where the injected name collides with one the image already
reads, the injected value wins.

`ldap` collides head-on. `osixia/openldap` reads `LDAP_PORT` to decide what to
listen on, is handed `tcp://10.43.x.x:389` where it expected `389`, and builds
itself a listen URL out of the result:

```
daemon: listen URL "ldap://ldap-6499ff475d-6g8dz:tcp://10.43.188.91:389" parse error=5
slapd stopped.
```

Every start, forever, with a `CrashLoopBackOff` and a message that names neither
Kubernetes nor the manifest. Nothing here is misconfigured and nothing in that
image is broken; they are wrong together, and only because a Service is called
`ldap`. Renaming the Service would also fix it, and would fix it by accident.

The one to keep an eye on is `orknux-server`, which injects `ORKNUX_SERVER_PORT`
and its relatives into every pod in the namespace — including the server's own,
where every setting the product has is an `ORKNUX_` variable. Nothing collides
today. A setting named `ORKNUX_SERVER_ANYTHING` would, it would be injected over
whatever the manifest said, and nothing would report it.

Nothing in this deployment wants these variables. Every address in the manifest
is a DNS name, which is why turning the whole mechanism off costs nothing.

## Getting to it properly

The manifest publishes nothing outside the cluster. `port-forward` is how you
look at it; an Ingress is how you keep it.

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: orknux
  namespace: orknux
  annotations:
    # An attachment may be 25MB and the server accepts a 26MB request. An
    # ingress controller has a limit of its own — 1MB on ingress-nginx — and
    # without this the upload fails at the edge with a 413 the server never
    # sees. Read **Attachments larger than a megabyte** below before deciding
    # this is the only place that limit is set.
    nginx.ingress.kubernetes.io/proxy-body-size: "30m"
spec:
  ingressClassName: nginx
  tls:
    - hosts: ["orknux.example.com"]
      secretName: orknux-tls
  rules:
    - host: orknux.example.com
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: orknux-ui
                port:
                  number: 8080
```

Route everything to `orknux-ui` and nothing to `orknux-server`. The interface
forwards `/api`, `/graphql` and `/mcp` itself, and that is what keeps the
browser on one origin and the session cookie first-party. A second rule sending
`/api` straight to the server would give you two addresses for the same server,
and a cookie set at one of them that the other cannot use.

Two things to set once a host exists:

- **`ORKNUX_BASE_URL`** on the server, to `https://orknux.example.com`. It is
  what a mailed password reset link points at, and it is configured rather than
  read off the `Host` header because that header is written by whoever is
  calling and the link opens an account. Empty writes no link and sends none.
- **`ORKNUX_SESSION_COOKIE_SAME_SITE=strict`**, if nothing links into Orknux
  from elsewhere. Leave it `lax` if a link from Slack or an email is expected to
  arrive already signed in.

Make sure the controller sets `X-Forwarded-For` and `X-Forwarded-Proto` —
ingress-nginx does — or the audit log attributes every action to the proxy
rather than to the person.

### Attachments larger than a megabyte

**The annotation above is necessary and is not sufficient, and the reason is not
Kubernetes.** `orknux/orknux-ui` sets no `client_max_body_size`, so the nginx
inside it holds to nginx's own default of 1MB — while the settings screen offers
25MB and the server accepts a 26MB request. Every attachment above about a
megabyte is refused by the interface with a 413 the server never sees, whether
that interface is reached through an Ingress, a `port-forward`, or
`docker compose`.

The all-in-one `orknux-one` image does not have this problem: `docker/one/nginx.conf`
in this repository sets `client_max_body_size 30m`, with a comment saying
precisely what happens without it. The fix was made in the image that carries
its own nginx configuration and not in the one that carries only a server block,
so the two disagree — and the one that disagrees is the one a deployment runs.

Until `orknux-ui` sets it, a deployment that needs large attachments has to add
it from outside. Files in `/etc/nginx/conf.d` are included into the `http`
block, and `client_max_body_size` is valid there, so one more file is enough:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: orknux-ui-body-size
  namespace: orknux
data:
  # Sorted before default.conf, and in the http block rather than in a server
  # block, so it applies to everything this nginx serves.
  00-body-size.conf: |
    client_max_body_size 30m;
```

mounted into the interface's container with `subPath`, so it lands beside the
rendered `default.conf` instead of replacing the directory that holds it:

```yaml
          volumeMounts:
            - name: body-size
              mountPath: /etc/nginx/conf.d/00-body-size.conf
              subPath: 00-body-size.conf
              readOnly: true
      volumes:
        - name: body-size
          configMap:
            name: orknux-ui-body-size
```

This is deliberately not in `orknux.yaml`. It is a workaround for something that
belongs in the interface image, and a third place that sets this limit is a
third place that can disagree with the other two — which is how there came to be
two. Take it if you need it now, and drop it again when the image sets its own.

## Why the server is one replica

Most of the server is happy with more than one, and it is worth knowing which
parts, because the thing that stops it is smaller than it looks:

- **Sessions are rows in the database**, so signing in survives whichever pod
  answers next, and a restart does not sign anybody out.
- **db-scheduler keeps its state there too.** One instance fires a schedule
  however many are running; that is what it is for.
- **More Temporal workers is how Temporal is meant to be scaled.** Two servers
  polling the same task queue is the normal arrangement, not a conflict.

**Slack is what stops it.** `SlackListener` opens one websocket per workspace
connection *per process*. Two pods is two deliveries of every mention — two runs
from one message, not one run twice as fast — and nothing downstream
de-duplicates them, because nothing was ever asked to.

So `replicas: 2` is safe only where no workspace has a Slack connection and none
will. Two other things have to move first if you go there anyway: the
attachments claim has to become `ReadWriteMany` or an object store, since
`ReadWriteOnce` will not mount on two nodes; and the strategy can go back to
`RollingUpdate` once nothing owns a claim exclusively.

The interface is the half that scales freely. It holds nothing, and it is
`replicas: 2` here already.

## Where the data lives

Four claims, and losing any of them means something different:

| Claim | Holds | If it goes |
| --- | --- | --- |
| `data-postgres-0` | Everything Orknux and Temporal know. | Everything, including credentials the secret key was protecting. |
| `ldap-data`, `ldap-config` | The directory, if you are using the one in this file. | The seeded people. The bootstrap LDIF is applied again on the next empty start. |
| `orknux-data` | Attached files, under `/home/orknux/attachments`. | The attachments. Nothing else. |

`orknux-data` is mounted at the server user's home directory rather than at
something tidier like `/app/data`, and that is not a style choice. The image
runs as uid 999; `/home/orknux` exists in it and belongs to that user, so a
claim mounted there inherits somewhere the server may write. Mounted where the
image has nothing, the directory belongs to root and the server cannot write a
single attachment — and it fails when somebody attaches a file, not at startup.
`fsGroup: 999` in the pod's security context is the other half of that, and is
why the volume itself is writable.

Postgres is the same story with different numbers: PGDATA is
`/var/lib/postgresql/18/docker` and Postgres 18 declares its volume one level
up, so the claim is mounted at `/var/lib/postgresql`. Mounting `.../data`
instead silently keeps nothing — every restart re-initialises the database
somewhere that is not on the volume.

**Back up the Postgres claim, and back up the secret key separately from it.** A
backup of the database without the key restores everything except the
credentials, which is a restore that does not work.

Deleting the namespace deletes all four claims. That is how you start over, and
it is also how you lose everything.

## Before this is more than a demonstration

The defaults are chosen so the file runs when you apply it, not so it is safe
when you leave it. [`deploy/README.md`](../README.md) has the list that is true
everywhere — keep the secret key, replace the seeded people or use your own
directory or OIDC, change the passwords, put TLS in front of it. These are the
ones this file adds:

1. **Move the secret key out of a plain `Secret`**, if you run anything that
   makes that easy. It is the one value whose loss is not recoverable and whose
   disclosure is not contained.
2. **Change `db-password` and `ldap-admin-password`** in the `orknux` Secret.
   They are the obvious words, and the database one is only read when the
   Postgres claim is first created — so change it before the first apply, or
   change it in the database too.
3. **Stop using `temporalio/auto-setup`.** It applies the Temporal schema on
   every start, which is what makes it a one-line dependency here and what makes
   it wrong to keep: a restart should not be a migration. On Kubernetes that is
   `temporalio/server` as the Deployment and a `Job` that runs `temporal-sql-tool`
   once, before it.
4. **Add a NetworkPolicy.** Compose gets this for free by publishing one port;
   here, every Service is reachable from every pod in the cluster, and Postgres,
   LDAP and Temporal have no business answering anything but the server. One
   `default-deny` for ingress in the namespace plus a rule per service is the
   shape. There is none in `orknux.yaml` on purpose: a policy is enforced by the
   CNI, and shipping one that a cluster without a policy-capable CNI silently
   ignores would be worse than shipping none — it would read as protection that
   is not there.
5. **Run Postgres as something you back up.** A StatefulSet with one claim is a
   database, not a database service. An operator, or a managed Postgres with
   `ORKNUX_DB_URL` pointed at it, is what to do when the data starts mattering —
   and Temporal can share it exactly as it does here.
6. **Tune the requests.** The values in the file are enough to start and are not
   measured against your workload. The server is the one to watch: it is a JVM
   with a heap sized from its limit, so a limit raised without the request
   raised alongside it is a pod that is scheduled somewhere it will not fit.

## Upgrading

```
kubectl -n orknux set image deploy/orknux-server orknux-server=orknux/orknux-server:0.9.9.5
kubectl -n orknux set image deploy/orknux-ui orknux-ui=orknux/orknux-ui:0.9.9.5
```

or edit both tags in the file and apply it again, which is the one that leaves
the cluster matching what is in git.

Flyway migrates on the way up, so the schema follows the server. JPA runs with
`ddl-auto: validate`, which means the migrations are the only thing that ever
changes the database and a mismatch is a startup failure rather than a strange
query. Take a copy of the Postgres claim first if you would mind going back.

**Move both tags together.** The interface and the server are one product
released under one version, and the interface is a bundle calling an API — the
version skew a rolling deploy normally tolerates is not a thing to rely on here.
`sha-<commit>` is the only tag that never moves, and is what to pin to if you
want to be certain what is running.

Both Orknux images are published for **linux/amd64 only**. On a mixed cluster,
that is a `nodeSelector` on `kubernetes.io/arch: amd64` away from being a pod
that schedules onto an arm64 node and does not start. Postgres, Temporal and
OpenLDAP all have native arm64 builds.

## When it does not come up

- **`CreateContainerConfigError` on `orknux-server`** — the guard doing its job.
  `orknux-secret-key` does not exist yet; the command is at the top of this
  page. `kubectl -n orknux describe pod -l app=orknux-server` says which key it
  could not find.
- **Stuck at `Init:0/1`** — the init container is still waiting for Postgres,
  LDAP or Temporal. `kubectl -n orknux logs <pod> -c wait-for-dependencies` says
  which one it is naming, and that is the service to look at next.
- **`orknux-server` restarts partway through a first start** — the startup probe
  ran out. Ten minutes is generous for that many migrations and mean for a slow
  volume; raise `failureThreshold` on the startup probe rather than the liveness
  one.
- **`Pending` claims** — usually nothing. Most default StorageClasses bind
  `WaitForFirstConsumer`, so a claim stays `Pending` until a pod that uses it is
  scheduled, and the three claims here are `Pending` for as long as the init
  container is waiting. It is a problem only if it outlasts the pod: then the
  cluster has no default StorageClass, or none that provisions
  `ReadWriteOnce`. `kubectl get storageclass` says what there is to point them
  at, and `kubectl -n orknux describe pvc` says what it objected to.
- **A second `orknux-server` pod stuck `ContainerCreating` after an edit** — the
  strategy was changed to `RollingUpdate` somewhere. Two pods cannot mount
  `orknux-data`; put it back to `Recreate`.
- **502 from `/api` while the server is healthy** — nginx in the interface is
  holding an address that has gone. It resolves once at startup, so this is what
  a headless Service, or a Service deleted and recreated, looks like from the
  browser. `kubectl -n orknux rollout restart deploy/orknux-ui`, then fix the
  Service it points at.
- **Uploads fail at about a megabyte** — a body limit, and there are two of them
  in the path. The ingress controller's is the annotation in the Ingress above;
  the interface image's own is nginx's 1MB default, which it does not override.
  **Attachments larger than a megabyte** above has both.
- **`ldap` in `CrashLoopBackOff` with `parse error=5`** — `enableServiceLinks`
  has been dropped from that pod, or the Deployment was copied without it. See
  **Every pod sets `enableServiceLinks: false`** above; the message names a
  listen URL with a `tcp://` in the middle of it.
- **Sign-in is refused** — the bootstrap LDIF is only applied when `ldap-data`
  is empty, so a claim left over from an earlier attempt with different settings
  will not have it. `kubectl -n orknux logs deploy/ldap`. A directory that
  crash-looped on its first start and was then fixed is exactly that case: the
  volume is half-written and the fixtures were never applied, so delete both
  `ldap` claims and let it start again.
- **Everything works until you save a provider key** — the secret key is wrong
  rather than missing. Admin -> Doctor says which.

## Why this is not a Helm chart

Because there is nothing to configure that is not an environment variable, and a
chart would be a second description of this deployment that can disagree with
the first. The compose file, this manifest and `DOCKERHUB.md` already have to
say the same things about the same settings; a `values.yaml` and a tree of
templates would make it four, and the one people read would be the one generated
from the others.

If you already run Helm or Kustomize, this file is a base to point them at. A
`kustomization.yaml` with a patch per environment is a few lines and keeps one
description of what Orknux is; a chart is a fork of it.
