# NeuroPlan DR Kustomize 구성

InfraReady 온프레미스 프로젝트의 **DR k3s 환경에 NeuroPlan 애플리케이션을 배포하기 위한 Kustomize Overlay**입니다.

공통 Kubernetes 리소스는 `../base`를 재사용하고, DR 환경에서 필요한 Gateway/HTTPRoute, Replica 수, PodDisruptionBudget(PDB), Container Image Tag만 별도로 정의합니다.

이 디렉터리는 Main Argo CD의 DR Application인 `neuroplan-login-mvp-dr`이 GitOps Source Path로 사용하며, Jenkins CI가 애플리케이션 빌드 후 `kustomization.yaml`의 `images[].newTag`를 자동 갱신합니다.

---

## 주요 기능

- `../base`의 공통 NeuroPlan Workload 재사용
- DR k3s 전용 Gateway 및 HTTPRoute 구성
- `app.nplan.local` HTTPS 진입 경로 구성
- Frontend / Backend Replica를 DR 환경에 맞게 각각 `1`로 조정
- DR 단일 노드 환경에 맞춰 PDB `minAvailable: 0` 적용
- Harbor Private Registry Image 사용
- Frontend / Backend Image Tag를 Kustomize `newTag`로 관리
- Jenkins가 Git Commit의 **7자리 Short SHA**를 Image Tag로 생성
- Jenkins가 변경된 애플리케이션의 `newTag`만 자동 갱신
- Main(On-Prem)과 DR Kustomize Image Tag를 동일 Pipeline에서 함께 갱신
- Jenkins가 Kustomize Rendering 검증 후 변경사항을 Git에 Commit / Push
- Argo CD Auto Sync를 통해 Git 변경사항을 DR k3s에 자동 반영

---

## 디렉터리 구조

```text
neuroplan-login-mvp/k8s/
├── base/
│   ├── 10-workloads.yaml
│   └── kustomization.yaml
│
├── onprem/
│   └── ...
│
└── dr/
    ├── 20-gateway-route.yaml
    ├── 30-replicas-patch.yaml
    ├── 31-pdb-patch.yaml
    └── kustomization.yaml
```

DR Overlay는 공통 `base`를 그대로 재사용하면서 DR에 필요한 차이만 Patch와 추가 Resource로 관리합니다.

---

## 파일 구성

| 파일 | 역할 |
|---|---|
| [`kustomization.yaml`](kustomization.yaml) | Base Resource, DR Resource, Patch 및 Harbor Image Tag 구성 |
| [`20-gateway-route.yaml`](20-gateway-route.yaml) | DR k3s의 NGINX Gateway Fabric용 Gateway / HTTPRoute 구성 |
| [`30-replicas-patch.yaml`](30-replicas-patch.yaml) | Frontend / Backend Deployment Replica를 각각 1개로 조정 |
| [`31-pdb-patch.yaml`](31-pdb-patch.yaml) | DR 단일 노드 환경에 맞춰 Frontend / Backend PDB `minAvailable: 0` 적용 |

공통 Deployment, Service, ConfigMap, PDB 정의는 다음 Base Manifest에서 관리합니다.

```text
../base/10-workloads.yaml
```

---

## Kustomize 구조

현재 `kustomization.yaml`의 기본 구조는 다음과 같습니다.

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: application

resources:
  - ../base
  - 20-gateway-route.yaml

patches:
  - path: 30-replicas-patch.yaml
  - path: 31-pdb-patch.yaml

images:
  - name: harbor.nplan.local:80/neuroplan/frontend
    newTag: "<frontend-git-sha>"

  - name: harbor.nplan.local:80/neuroplan/backend
    newTag: "<backend-git-sha>"
```

`namespace`는 다음 값으로 고정되어 있습니다.

```text
application
```

---

## Base와 DR Overlay 관계

공통 애플리케이션 구성은 `../base`에서 관리합니다.

```text
Base
 │
 ├── Backend ConfigMap
 ├── Frontend Deployment
 ├── Frontend Service
 ├── Backend Deployment
 ├── Backend Service
 ├── Frontend PDB
 └── Backend PDB
      │
      ▼
DR Overlay
 │
 ├── Gateway / HTTPRoute 추가
 ├── Replica 1로 조정
 ├── PDB minAvailable 0으로 조정
 └── Harbor Image Tag 적용
```

이 구조를 통해 Main과 DR이 동일한 애플리케이션 정의를 공유하면서 환경별 차이만 Overlay에서 관리합니다.

---

## DR Replica 정책

Base에서는 Frontend와 Backend Deployment가 각각 `replicas: 2`로 정의되어 있습니다.

DR 환경에서는 `30-replicas-patch.yaml`을 이용해 다음과 같이 변경합니다.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: neuroplan-frontend
spec:
  replicas: 1

---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: neuroplan-backend
spec:
  replicas: 1
```

최종 DR Replica 구성:

```text
Frontend : 1
Backend  : 1
```

DR은 Main Kubernetes와 동일 규모의 클러스터가 아니라 단일 k3s 기반 환경이므로, Overlay에서 Replica 수를 별도로 조정합니다.

---

## DR PDB 정책

Base의 PDB는 다음과 같이 구성되어 있습니다.

```text
Frontend minAvailable : 1
Backend  minAvailable : 1
```

DR Overlay에서는 `31-pdb-patch.yaml`을 통해 다음 값으로 변경합니다.

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: neuroplan-frontend
spec:
  minAvailable: 0

---
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: neuroplan-backend
spec:
  minAvailable: 0
```

최종 DR 설정:

```text
Frontend minAvailable : 0
Backend  minAvailable : 0
```

이는 단일 Replica로 운영되는 DR 환경에서 PDB가 Pod 운영 작업을 불필요하게 차단하지 않도록 하기 위한 Overlay 설정입니다.

---

## Gateway / HTTPRoute

DR 환경에서는 `20-gateway-route.yaml`을 통해 Gateway API 기반 서비스 진입 경로를 구성합니다.

### Gateway

```text
Name         : neuroplan-gateway
GatewayClass : nginx
Protocol     : HTTPS
Port         : 443
Hostname     : app.nplan.local
TLS Secret   : nplan-tls-v2
```

주요 구성:

```yaml
gatewayClassName: nginx

listeners:
  - name: https
    hostname: app.nplan.local
    port: 443
    protocol: HTTPS
```

TLS는 Gateway에서 종료합니다.

```yaml
tls:
  mode: Terminate
  certificateRefs:
    - kind: Secret
      name: nplan-tls-v2
```

---

## HTTPRoute

HTTPRoute 이름:

```text
neuroplan-login-mvp
```

Hostname:

```text
app.nplan.local
```

요청 경로는 다음과 같이 분리합니다.

```text
/api
  │
  ▼
neuroplan-backend:8080

/
  │
  ▼
neuroplan-frontend:80
```

Backend API:

```yaml
- matches:
    - path:
        type: PathPrefix
        value: /api
  backendRefs:
    - name: neuroplan-backend
      port: 8080
```

Frontend:

```yaml
- matches:
    - path:
        type: PathPrefix
        value: /
  backendRefs:
    - name: neuroplan-frontend
      port: 80
```

---

# Jenkins 기반 Image Tag 자동 갱신

이 디렉터리의 `kustomization.yaml`에 있는 `newTag` 값은 **운영자가 매번 수동으로 변경하는 값이 아니라 Jenkins CI Pipeline에 의해 자동 갱신되는 배포 상태 값**입니다.

Jenkins Pipeline은 Repository Root의 다음 파일에서 관리합니다.

```text
/Jenkinsfile
```

Jenkins는 Main과 DR Kustomize 파일 경로를 모두 Pipeline 변수로 정의합니다.

```text
Main:
neuroplan-login-mvp/k8s/onprem/kustomization.yaml

DR:
neuroplan-login-mvp/k8s/dr/kustomization.yaml
```

---

## Image Tag 생성 방식

Jenkins Checkout 단계에서 현재 애플리케이션 Git Commit의 **7자리 Short SHA**를 생성합니다.

논리적으로 다음과 같습니다.

```bash
git rev-parse --short=7 HEAD
```

생성된 값은 Pipeline에서 다음 변수로 사용합니다.

```text
IMAGE_TAG
```

예:

```text
db4a0fb
4dd7cc3
```

최종 Harbor Image 예:

```text
harbor.nplan.local:80/neuroplan/frontend:db4a0fb
harbor.nplan.local:80/neuroplan/backend:4dd7cc3
```

---

## Frontend / Backend 변경 감지

Jenkins는 Git 변경 파일을 확인해 Frontend와 Backend 변경 여부를 각각 판단합니다.

대상 경로:

```text
neuroplan-login-mvp/frontend/
neuroplan-login-mvp/backend/
```

따라서 Pipeline은 다음과 같이 동작할 수 있습니다.

```text
Frontend만 변경
→ Frontend만 Build / Push / newTag 변경

Backend만 변경
→ Backend만 Build / Push / newTag 변경

Frontend + Backend 변경
→ 두 Image 모두 Build / Push / newTag 변경

Kubernetes Manifest만 변경
→ Application Image Build Skip
```

---

## Frontend와 Backend Tag가 다를 수 있는 이유

`kustomization.yaml`에서 Frontend와 Backend의 `newTag` 값은 반드시 같을 필요가 없습니다.

예:

```yaml
images:
  - name: harbor.nplan.local:80/neuroplan/frontend
    newTag: "db4a0fb"

  - name: harbor.nplan.local:80/neuroplan/backend
    newTag: "4dd7cc3"
```

이것은 정상적인 상태입니다.

Jenkins가 **변경된 컴포넌트의 Image만 새로 Build**하고 해당 Image의 `newTag`만 변경하기 때문입니다.

예를 들어 Backend만 변경된 경우:

```text
기존 상태
Frontend : db4a0fb
Backend  : 1111111

Backend Source 변경
        │
        ▼
Git Commit: 4dd7cc3...
        │
        ▼
Backend Build / Push
        │
        ▼
Backend newTag만 변경
        │
        ▼
최종 상태
Frontend : db4a0fb
Backend  : 4dd7cc3
```

따라서 각각의 `newTag`는 해당 컴포넌트가 마지막으로 빌드된 Git Commit을 나타냅니다.

---

## Jenkins의 Kustomize Tag 변경

Image Build와 Harbor Push가 완료되면 Jenkins의 `Update Kustomize Image Tags` Stage가 실행됩니다.

Jenkins는 다음 두 파일을 동시에 대상으로 합니다.

```text
neuroplan-login-mvp/k8s/onprem/kustomization.yaml
neuroplan-login-mvp/k8s/dr/kustomization.yaml
```

변경된 Component에 대해서만 다음 값을 수정합니다.

```yaml
newTag: "<IMAGE_TAG>"
```

예:

```text
Frontend 변경 감지
        │
        ▼
IMAGE_TAG=db4a0fb
        │
        ├── onprem/kustomization.yaml
        │       Frontend newTag=db4a0fb
        │
        └── dr/kustomization.yaml
                Frontend newTag=db4a0fb
```

Main과 DR이 같은 Pipeline에서 동일하게 갱신되므로, 동일한 애플리케이션 Image 버전을 두 환경에서 사용할 수 있습니다.

---

## Jenkins Kustomize 검증

`newTag`를 변경한 뒤 Jenkins는 바로 Git에 Commit하지 않습니다.

먼저 다음 두 Overlay를 렌더링합니다.

```bash
kubectl kustomize neuroplan-login-mvp/k8s/onprem
kubectl kustomize neuroplan-login-mvp/k8s/dr
```

DR 결과는 Pipeline에서 다음 임시 파일로 저장합니다.

```text
/tmp/neuroplan-dr-rendered.yaml
```

Jenkins는 렌더링된 Manifest 안에 실제로 다음 Image가 존재하는지 확인합니다.

```text
harbor.nplan.local:80/neuroplan/frontend:<IMAGE_TAG>
harbor.nplan.local:80/neuroplan/backend:<IMAGE_TAG>
```

단, 실제로 변경된 Component만 검증 대상이 됩니다.

검증에 실패하면 Kustomize 변경사항을 Git에 Push하지 않습니다.

---

## Jenkins Git Commit / Push

Kustomize 렌더링 검증이 성공하면 Jenkins가 변경된 Main/DR `kustomization.yaml`을 Git에 Commit합니다.

Jenkins Git Identity:

```text
Name  : Jenkins CI
Email : jenkins@nplan.local
```

Commit Message:

```text
ci: update image tags to <IMAGE_TAG>
```

예:

```text
ci: update image tags to db4a0fb
```

이후 SSH Credential을 이용해 Repository의 `main` Branch로 Push합니다.

```text
Jenkins
   │
   ▼
git add
onprem/kustomization.yaml
dr/kustomization.yaml
   │
   ▼
git commit
   │
   ▼
git push HEAD:main
```

---

## Jenkins 자체 Commit 재감지

Jenkins가 Image Tag를 변경하고 Git에 Push하면 Repository에는 새로운 Commit이 발생합니다.

하지만 이 Commit은 주로 다음 파일만 변경합니다.

```text
k8s/onprem/kustomization.yaml
k8s/dr/kustomization.yaml
```

Jenkins의 Application 변경 감지는 다음 Source Directory만 확인합니다.

```text
neuroplan-login-mvp/frontend/
neuroplan-login-mvp/backend/
```

따라서 Jenkins가 자신이 만든 Kustomize Commit을 다시 감지하더라도 다음 상태가 됩니다.

```text
Frontend changed : false
Backend changed  : false
```

결과:

```text
Image Build Skip
Harbor Push Skip
Kustomize Tag Update Skip
```

이를 통해 Jenkins가 자신의 GitOps Commit 때문에 반복적으로 Image를 다시 Build하는 Loop를 방지합니다.

---

# CI/CD → GitOps 전체 흐름

DR 배포에서 `kustomization.yaml`은 Jenkins와 Argo CD를 연결하는 GitOps 상태 파일 역할을 합니다.

```text
Developer
   │
   │ Source Code 변경
   ▼
GitHub
   │
   ▼
Jenkins
   │
   ├── Frontend 변경 감지
   └── Backend 변경 감지
   │
   ▼
Docker Build
   │
   ▼
Harbor Push
harbor.nplan.local:80
   │
   │ Git Short SHA Tag
   ▼
Update Kustomize newTag
   │
   ├── k8s/onprem/kustomization.yaml
   └── k8s/dr/kustomization.yaml
   │
   ▼
kubectl kustomize Validation
   │
   ▼
Jenkins Git Commit / Push
   │
   ▼
GitHub main
   │
   ▼
Argo CD Auto Sync
   │
   ▼
DR k3s
192.168.34.71:6443
   │
   ▼
Namespace: application
   │
   ▼
NeuroPlan DR Deployment
```

---

## Harbor Image

DR 환경에서 사용하는 Container Image Repository:

```text
Frontend:
harbor.nplan.local:80/neuroplan/frontend

Backend:
harbor.nplan.local:80/neuroplan/backend
```

Base Deployment에서는 Tag 없이 Image Repository를 정의합니다.

```yaml
image: harbor.nplan.local:80/neuroplan/frontend
```

```yaml
image: harbor.nplan.local:80/neuroplan/backend
```

실제 Image Tag는 DR Overlay의 `kustomization.yaml`에서 `images.newTag`를 이용해 적용합니다.

즉 다음과 같이 역할이 분리됩니다.

```text
Base
→ Image Repository 정의

DR kustomization.yaml
→ 배포할 Image Tag 정의

Jenkins
→ newTag 자동 갱신
```

---

## Argo CD DR Application 연동

Main Argo CD의 DR Application:

```text
neuroplan-login-mvp-dr
```

GitOps Source:

```text
Repository:
Infrastructure-hybrid09/onprem-k8s-application-devopsVM

Revision:
main

Path:
neuroplan-login-mvp/k8s/dr
```

Destination:

```text
Server:
https://192.168.34.71:6443

Namespace:
application
```

DR Application은 Auto Sync를 사용하며 다음 정책이 활성화되어 있습니다.

```text
prune    : true
selfHeal : true
```

따라서 Jenkins가 `newTag` 변경을 Git에 Push하면 Argo CD가 해당 Git 상태를 Desired State로 감지해 DR k3s에 반영합니다.

---

## DR 배포 흐름

```text
Git
neuroplan-login-mvp/k8s/dr
        │
        ▼
Argo CD
neuroplan-login-mvp-dr
        │
        ▼
Kustomize Render
        │
        ├── ../base
        ├── 20-gateway-route.yaml
        ├── 30-replicas-patch.yaml
        ├── 31-pdb-patch.yaml
        └── images.newTag
        │
        ▼
DR k3s
        │
        ▼
application Namespace
        │
        ├── neuroplan-frontend
        ├── neuroplan-backend
        ├── Services
        ├── PDB
        ├── Gateway
        └── HTTPRoute
```

---

## 수동 Rendering 검증

Repository Root에서 다음 명령으로 DR Overlay의 최종 Manifest를 확인할 수 있습니다.

```bash
kubectl kustomize neuroplan-login-mvp/k8s/dr
```

파일로 저장:

```bash
kubectl kustomize neuroplan-login-mvp/k8s/dr \
  > /tmp/neuroplan-dr-rendered.yaml
```

적용될 Image 확인:

```bash
kubectl kustomize neuroplan-login-mvp/k8s/dr \
  | grep 'image:'
```

예상 형태:

```text
image: harbor.nplan.local:80/neuroplan/frontend:<frontend-tag>
image: harbor.nplan.local:80/neuroplan/backend:<backend-tag>
```

---

## 실제 클러스터 검증

DR k3s 배포 후 다음 명령으로 확인할 수 있습니다.

### Pod

```bash
kubectl get pods -n application
```

DR에서는 Frontend와 Backend가 각각 1개 Replica로 구성됩니다.

### Deployment Image

```bash
kubectl get deployment \
  neuroplan-frontend \
  neuroplan-backend \
  -n application \
  -o jsonpath='{range .items[*]}{.metadata.name}{" : "}{.spec.template.spec.containers[0].image}{"\n"}{end}'
```

### Gateway / HTTPRoute

```bash
kubectl get gateway -n application
kubectl get httproute -n application
```

### Argo CD

Main Kubernetes에서:

```bash
kubectl get application \
  neuroplan-login-mvp-dr \
  -n argocd
```

확인 항목:

```text
SYNC   : Synced
HEALTH : Healthy
```

---

## Image Tag 수동 수정 주의

`kustomization.yaml`의 `newTag`는 Jenkins Pipeline이 관리하는 값입니다.

따라서 일반적인 애플리케이션 배포 과정에서는 다음 값을 직접 수정하지 않는 것을 원칙으로 합니다.

```yaml
images:
  - name: harbor.nplan.local:80/neuroplan/frontend
    newTag: "..."

  - name: harbor.nplan.local:80/neuroplan/backend
    newTag: "..."
```

수동으로 변경하더라도 이후 Frontend 또는 Backend Source 변경으로 Jenkins Pipeline이 실행되면 해당 Component의 `newTag`는 새 Git SHA로 다시 갱신됩니다.

배포 Image 변경은 기본적으로 다음 흐름을 사용합니다.

```text
Source Code 변경
→ Jenkins Build
→ Harbor Push
→ Kustomize newTag 자동 변경
→ Git Commit / Push
→ Argo CD Auto Sync
```

---

## Main / DR Kustomize 관계

| 구분 | Main On-Prem | DR |
|---|---|---|
| Path | `k8s/onprem` | `k8s/dr` |
| Base | `../base` | `../base` |
| Namespace | `application` | `application` |
| Image Registry | Harbor | Harbor |
| Image Tag 관리 | Jenkins | Jenkins |
| Frontend Replica | Base 기준 | `1` |
| Backend Replica | Base 기준 | `1` |
| PDB | Base 기준 | `minAvailable: 0` |
| 배포 대상 | Main Kubernetes | DR k3s |
| Argo CD App | `neuroplan-login-mvp` | `neuroplan-login-mvp-dr` |

Jenkins는 애플리케이션 Image가 변경되면 **두 Overlay의 `newTag`를 함께 갱신**합니다.

---

## 관련 코드

- [Repository Jenkinsfile](../../../Jenkinsfile)
- [Base Kustomize](../base/)
- [Main On-Prem Kustomize](../onprem/)
- [DR Gateway / HTTPRoute](20-gateway-route.yaml)
- [DR Replica Patch](30-replicas-patch.yaml)
- [DR PDB Patch](31-pdb-patch.yaml)
- [DR Kustomization](kustomization.yaml)
- [Ansible Repository - DR Argo CD Application Role](https://github.com/Infrastructure-hybrid09/onprem-k8s-ansible/tree/main/roles/argocd_dr_application)

---

## 최종 DR GitOps 구성

```text
Source Code
Frontend / Backend
       │
       ▼
    Jenkins
       │
       ├── Change Detection
       ├── Docker Build
       ├── Harbor Push
       └── Git Short SHA 생성
       │
       ▼
k8s/dr/kustomization.yaml
       │
       └── images[].newTag 자동 갱신
       │
       ▼
 Jenkins Validation
 kubectl kustomize
       │
       ▼
Git Commit / Push
       │
       ▼
Argo CD
neuroplan-login-mvp-dr
       │
       ▼
DR k3s
192.168.34.71
       │
       ▼
application Namespace
       │
       ├── Frontend x1
       ├── Backend x1
       ├── Gateway :443
       └── HTTPRoute
             │
             ▼
       app.nplan.local
```
