# NeuroPlan On-Prem Kustomize 구성

InfraReady 온프레미스 Kubernetes 환경에 **NeuroPlan 애플리케이션을 배포하기 위한 Kustomize Overlay**입니다.

공통 Kubernetes 리소스는 `../base`에서 재사용하고, On-Prem 환경에서 필요한 HTTPRoute와 Container Image Tag를 이 디렉터리에서 관리합니다.

이 경로는 Main Argo CD Application인 `neuroplan-login-mvp`의 GitOps 배포 경로로 사용되며, 애플리케이션 Source가 변경되면 Jenkins CI Pipeline이 Harbor에 새 이미지를 Push한 뒤 `kustomization.yaml`의 `images[].newTag` 값을 자동으로 변경합니다.

즉 이 디렉터리의 Image Tag는 일반적으로 운영자가 직접 수정하는 값이 아니라 **Jenkins가 CI 결과를 GitOps Desired State로 기록하는 값**입니다.

---

## 주요 기능

- `../base`의 공통 NeuroPlan Workload 재사용
- `application` Namespace에 애플리케이션 배포
- On-Prem 전용 HTTPRoute 구성
- `app.nplan.local` 요청을 Frontend / Backend로 분기
- `/api` 요청의 API Timeout 별도 설정
- Harbor Private Registry Image 사용
- Frontend / Backend Image Tag를 Kustomize `newTag`로 관리
- Jenkins가 Git Commit의 **7자리 Short SHA**를 Image Tag로 생성
- Jenkins가 변경된 Component만 Build / Push
- Jenkins가 On-Prem `kustomization.yaml`의 `newTag`를 자동 수정
- DR `kustomization.yaml`도 같은 Pipeline에서 함께 갱신
- `kubectl kustomize` Rendering 검증 후 Git Commit / Push
- Argo CD가 Git 변경사항을 감지해 Main Kubernetes에 자동 반영

---

## 디렉터리 구조

```text
neuroplan-login-mvp/k8s/
├── base/
│   ├── 10-workloads.yaml
│   └── kustomization.yaml
│
├── onprem/
│   ├── 20-httproute.yaml
│   └── kustomization.yaml
│
└── dr/
    ├── 20-gateway-route.yaml
    ├── 30-replicas-patch.yaml
    ├── 31-pdb-patch.yaml
    └── kustomization.yaml
```

On-Prem Overlay는 DR Overlay와 달리 Replica와 PDB를 별도로 Patch하지 않습니다.

따라서 `../base`에 정의된 기본 Replica 및 PDB 정책을 그대로 사용합니다.

---

## 파일 구성

| 파일 | 역할 |
|---|---|
| [`kustomization.yaml`](kustomization.yaml) | Base Resource, On-Prem HTTPRoute 및 Harbor Image Tag 구성 |
| [`20-httproute.yaml`](20-httproute.yaml) | `app.nplan.local` 요청을 Frontend / Backend Service로 전달하는 HTTPRoute |

공통 Deployment, Service, ConfigMap, PDB 정의는 다음 Base Manifest에서 관리합니다.

```text
../base/10-workloads.yaml
```

---

## Kustomize 구성

현재 `kustomization.yaml`의 구조는 다음과 같습니다.

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization

namespace: application

resources:
  - ../base
  - 20-httproute.yaml

images:
  - name: harbor.nplan.local:80/neuroplan/frontend
    newTag: "<frontend-git-sha>"

  - name: harbor.nplan.local:80/neuroplan/backend
    newTag: "<backend-git-sha>"
```

현재 Namespace:

```text
application
```

현재 Image Repository:

```text
harbor.nplan.local:80/neuroplan/frontend
harbor.nplan.local:80/neuroplan/backend
```

---

## Base와 On-Prem Overlay 관계

공통 Workload는 `../base`에서 관리합니다.

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
On-Prem Overlay
 │
 ├── HTTPRoute 추가
 └── Harbor Image Tag 적용
```

On-Prem 환경은 Base Deployment의 Replica 및 PDB 설정을 그대로 유지합니다.

Base 기준:

```text
Frontend Replica : 2
Backend Replica  : 2

Frontend PDB minAvailable : 1
Backend  PDB minAvailable : 1
```

즉 DR과 달리 On-Prem Overlay에서는 별도의 Replica/PDB Patch가 없습니다.

---

## Main / DR Overlay 차이

| 구분 | On-Prem | DR |
|---|---|---|
| Base | `../base` | `../base` |
| Namespace | `application` | `application` |
| Frontend Replica | Base의 `2` 유지 | `1`로 Patch |
| Backend Replica | Base의 `2` 유지 | `1`로 Patch |
| PDB | Base의 `minAvailable: 1` 유지 | `minAvailable: 0` |
| Route | HTTPRoute 추가 | Gateway + HTTPRoute 추가 |
| Image Registry | Harbor | Harbor |
| Image Tag 관리 | Jenkins | Jenkins |
| GitOps Path | `k8s/onprem` | `k8s/dr` |

두 환경 모두 동일한 애플리케이션 Base를 사용하고, 환경별 차이만 Overlay에서 관리합니다.

---

# HTTPRoute 구성

On-Prem 환경에서는 `20-httproute.yaml`을 이용해 NeuroPlan 서비스 라우팅을 구성합니다.

HTTPRoute 이름:

```text
neuroplan-login-mvp
```

Hostname:

```text
app.nplan.local
```

연결 Gateway:

```text
neuroplan-gateway
```

Listener:

```text
https
```

HTTPRoute는 다음 Gateway를 참조합니다.

```yaml
parentRefs:
  - name: neuroplan-gateway
    sectionName: https
```

이 디렉터리에는 Gateway Resource 자체가 포함되어 있지 않으므로, `neuroplan-gateway`가 클러스터에 먼저 존재해야 HTTPRoute가 정상적으로 연결될 수 있습니다.

---

## 요청 경로

요청은 다음과 같이 분기합니다.

```text
app.nplan.local
       │
       ├── /api
       │      │
       │      ▼
       │ neuroplan-backend:8080
       │
       └── /
              │
              ▼
         neuroplan-frontend:80
```

### Backend API

```yaml
- matches:
    - path:
        type: PathPrefix
        value: /api

  backendRefs:
    - name: neuroplan-backend
      port: 8080
```

### Frontend

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

## API Timeout

`/api` 요청에는 다음 Timeout 설정이 적용되어 있습니다.

```yaml
timeouts:
  request: 120s
  backendRequest: 110s
```

정리:

```text
전체 Request Timeout  : 120초
Backend Request       : 110초
```

Frontend `/` Route에는 별도의 Timeout이 정의되어 있지 않습니다.

---

# Jenkins 기반 Image Tag 자동 갱신

이 디렉터리의 `kustomization.yaml`에서 가장 중요한 부분은 다음 `images` 항목입니다.

```yaml
images:
  - name: harbor.nplan.local:80/neuroplan/frontend
    newTag: "..."

  - name: harbor.nplan.local:80/neuroplan/backend
    newTag: "..."
```

이 `newTag`는 일반적인 배포 과정에서 직접 수정하는 값이 아니라 **Jenkins CI Pipeline에 의해 자동 변경되는 배포 상태 값**입니다.

Jenkins Pipeline은 Repository Root의 다음 파일에서 관리됩니다.

```text
/Jenkinsfile
```

---

## Jenkins가 관리하는 Kustomize 파일

Jenkinsfile에서는 Main과 DR Kustomize 경로를 모두 Pipeline 환경 변수로 정의합니다.

```text
KUSTOMIZATION_ONPREM
= neuroplan-login-mvp/k8s/onprem/kustomization.yaml

KUSTOMIZATION_DR
= neuroplan-login-mvp/k8s/dr/kustomization.yaml
```

즉 하나의 Jenkins Pipeline이 다음 두 환경의 Image Tag를 함께 관리합니다.

```text
Jenkins
   │
   ├── On-Prem Kustomize
   │   k8s/onprem/kustomization.yaml
   │
   └── DR Kustomize
       k8s/dr/kustomization.yaml
```

---

## Image Tag 생성 방식

Jenkins Checkout 단계에서 현재 Git Commit의 **7자리 Short SHA**를 Image Tag로 사용합니다.

실제 Pipeline의 논리:

```bash
git rev-parse --short=7 HEAD
```

결과는 다음 환경 변수에 저장됩니다.

```text
IMAGE_TAG
```

예:

```text
db4a0fb
4dd7cc3
```

Harbor에 Push되는 최종 Image 예:

```text
harbor.nplan.local:80/neuroplan/frontend:db4a0fb
harbor.nplan.local:80/neuroplan/backend:4dd7cc3
```

Git Commit과 배포 Image를 연결할 수 있기 때문에 어떤 Source Commit으로 만들어진 Image인지 추적할 수 있습니다.

---

## Frontend / Backend 변경 감지

Jenkins는 Repository 변경 파일을 확인해 Frontend와 Backend 변경 여부를 각각 판단합니다.

검사 대상:

```text
neuroplan-login-mvp/frontend/
neuroplan-login-mvp/backend/
```

Pipeline은 다음 변수로 결과를 관리합니다.

```text
FRONTEND_CHANGED
BACKEND_CHANGED
```

실행 방식:

```text
Frontend만 변경
→ Frontend Build / Push
→ Frontend newTag 변경

Backend만 변경
→ Backend Build / Push
→ Backend newTag 변경

Frontend + Backend 변경
→ 둘 다 Build / Push
→ 두 newTag 모두 변경

Manifest만 변경
→ Application Image Build Skip
```

---

## 변경된 Component만 Build

Frontend가 변경된 경우에만 다음 Image를 Build합니다.

```text
harbor.nplan.local:80/neuroplan/frontend:<IMAGE_TAG>
```

Backend가 변경된 경우에만 다음 Image를 Build합니다.

```text
harbor.nplan.local:80/neuroplan/backend:<IMAGE_TAG>
```

따라서 변경되지 않은 Component는 기존 Image Tag를 그대로 유지합니다.

---

## Frontend / Backend newTag가 서로 다를 수 있는 이유

현재 `kustomization.yaml`처럼 Frontend와 Backend의 Tag가 서로 다른 것은 정상입니다.

예:

```yaml
images:
  - name: harbor.nplan.local:80/neuroplan/frontend
    newTag: "db4a0fb"

  - name: harbor.nplan.local:80/neuroplan/backend
    newTag: "4dd7cc3"
```

Jenkins가 **변경된 Component에 대해서만 새 Image를 Build하고 해당 `newTag`만 변경하기 때문**입니다.

예를 들어 Backend Source만 변경되었다고 가정하면:

```text
기존 상태

Frontend : db4a0fb
Backend  : abc1234

        │
        │ Backend Source 변경
        ▼

Git Commit
4dd7cc3...

        │
        ▼

Backend Build
harbor.nplan.local:80/neuroplan/backend:4dd7cc3

        │
        ▼

Backend newTag만 갱신

        │
        ▼

최종 상태

Frontend : db4a0fb
Backend  : 4dd7cc3
```

따라서 각 Tag는 해당 Component가 마지막으로 Build된 Git Commit을 나타냅니다.

---

# Jenkins의 Kustomize 파일 변경

Image Build와 Harbor Push가 완료되면 Jenkins의 다음 Stage가 실행됩니다.

```text
Update Kustomize Image Tags
```

이 단계에서 Jenkins는 다음 두 파일을 읽습니다.

```text
neuroplan-login-mvp/k8s/onprem/kustomization.yaml
neuroplan-login-mvp/k8s/dr/kustomization.yaml
```

그리고 실제 Source가 변경된 Image만 찾아 다음 값을 변경합니다.

```yaml
newTag: "<IMAGE_TAG>"
```

---

## Frontend 변경 예

```text
Frontend Source 변경
       │
       ▼
IMAGE_TAG = db4a0fb
       │
       ▼
Frontend Image Build
       │
       ▼
Harbor Push
       │
       ├── onprem/kustomization.yaml
       │     Frontend newTag = db4a0fb
       │
       └── dr/kustomization.yaml
             Frontend newTag = db4a0fb
```

Backend Tag는 변경되지 않습니다.

---

## Backend 변경 예

```text
Backend Source 변경
       │
       ▼
IMAGE_TAG = 4dd7cc3
       │
       ▼
Backend Image Build
       │
       ▼
Harbor Push
       │
       ├── onprem/kustomization.yaml
       │     Backend newTag = 4dd7cc3
       │
       └── dr/kustomization.yaml
             Backend newTag = 4dd7cc3
```

Frontend Tag는 변경되지 않습니다.

---

## Main과 DR Tag 동시 반영

Jenkins는 동일 Component가 변경되면 On-Prem과 DR의 해당 Image Tag를 같은 값으로 갱신합니다.

```text
Application Source
       │
       ▼
Jenkins Build
       │
       ▼
Harbor Image
<IMAGE_TAG>
       │
       ├── On-Prem newTag
       │
       └── DR newTag
```

따라서 새로 Build된 동일 Image 버전을 Main과 DR 환경에서 공유할 수 있습니다.

---

# Kustomize Rendering 검증

Jenkins는 `newTag`를 수정한 직후 바로 Git Push하지 않습니다.

먼저 On-Prem과 DR Overlay를 모두 Rendering합니다.

On-Prem:

```bash
kubectl kustomize \
  neuroplan-login-mvp/k8s/onprem
```

DR:

```bash
kubectl kustomize \
  neuroplan-login-mvp/k8s/dr
```

Pipeline에서는 각각 다음 파일에 저장합니다.

```text
/tmp/neuroplan-onprem-rendered.yaml
/tmp/neuroplan-dr-rendered.yaml
```

---

## Image 검증

Frontend가 변경된 경우 Jenkins는 Rendering 결과에 다음 Image가 실제 존재하는지 확인합니다.

```text
harbor.nplan.local:80/neuroplan/frontend:<IMAGE_TAG>
```

Backend가 변경된 경우:

```text
harbor.nplan.local:80/neuroplan/backend:<IMAGE_TAG>
```

검증 대상은 On-Prem과 DR 모두입니다.

```text
On-Prem Render
      │
      ├── Image Tag 검증
      │
DR Render
      │
      └── Image Tag 검증
              │
              ▼
          검증 성공
              │
              ▼
        Git Commit 진행
```

검증에 실패하면 Kustomize 변경을 정상적인 GitOps 변경사항으로 Commit하지 않습니다.

---

# Jenkins Git Commit / Push

Kustomize 검증이 완료되면 Jenkins가 변경된 파일을 Git에 Commit합니다.

대상 파일:

```text
neuroplan-login-mvp/k8s/onprem/kustomization.yaml
neuroplan-login-mvp/k8s/dr/kustomization.yaml
```

Jenkins Git Identity:

```text
Name  : Jenkins CI
Email : jenkins@nplan.local
```

Commit Message 형식:

```text
ci: update image tags to <IMAGE_TAG>
```

예:

```text
ci: update image tags to db4a0fb
```

이후 SSH Credential을 이용해 Repository의 `main` Branch로 Push합니다.

```text
git add
   │
   ▼
git commit
   │
   ▼
git push HEAD:main
```

---

# Jenkins 자체 Commit 재감지 방지

Jenkins가 `kustomization.yaml`을 변경하고 `main` Branch에 Push하면 새로운 Git Commit이 생성됩니다.

그러나 Jenkins의 Source 변경 감지는 다음 디렉터리를 기준으로 합니다.

```text
neuroplan-login-mvp/frontend/
neuroplan-login-mvp/backend/
```

Jenkins 자신이 만든 Commit은 일반적으로 다음 파일만 변경합니다.

```text
k8s/onprem/kustomization.yaml
k8s/dr/kustomization.yaml
```

따라서 다음 Pipeline 실행에서:

```text
Frontend changed : false
Backend changed  : false
```

가 되어 Image Build를 다시 수행하지 않습니다.

결과적으로 다음과 같은 재귀적인 CI Loop를 방지합니다.

```text
Jenkins Tag Commit
      │
      ▼
Jenkins 재감지
      │
      ▼
Source 변경 없음
      │
      ▼
Build Skip
```

---

# CI/CD → GitOps 전체 흐름

On-Prem 배포에서는 `kustomization.yaml`이 Jenkins의 CI 결과를 Argo CD의 GitOps Desired State로 연결하는 역할을 합니다.

```text
Developer
   │
   │ Git Push
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
   ▼
Git Short SHA Image Tag
   │
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
Main Kubernetes
   │
   ▼
Namespace: application
   │
   ▼
NeuroPlan Rolling Deployment
```

---

## Jenkins와 Argo CD 역할 분리

CI/CD에서 Jenkins와 Argo CD의 역할은 분리되어 있습니다.

```text
Jenkins
→ Source 변경 감지
→ Image Build
→ Harbor Push
→ Kustomize newTag 변경
→ Git Commit / Push

Argo CD
→ Git Repository 감시
→ Kustomize Desired State 확인
→ Main Kubernetes에 Sync
```

즉 Jenkins가 Kubernetes Deployment를 직접 수정하는 것이 아니라 **Git의 `kustomization.yaml`을 변경**하고, 실제 Kubernetes 배포는 Argo CD가 Git 상태를 기준으로 수행합니다.

이 구조가 GitOps 방식의 핵심입니다.

---

# Harbor Image 관리

Base Deployment에는 Image Repository가 다음과 같이 정의되어 있습니다.

Frontend:

```yaml
image: harbor.nplan.local:80/neuroplan/frontend
```

Backend:

```yaml
image: harbor.nplan.local:80/neuroplan/backend
```

실제 배포 Tag는 On-Prem Overlay의 `kustomization.yaml`에서 적용합니다.

```text
Base
→ Image Repository 정의

On-Prem kustomization.yaml
→ 배포할 Image Tag 정의

Jenkins
→ newTag 자동 갱신
```

---

## Image Tag를 Overlay에서 관리하는 이유

Deployment Manifest에 Image Tag를 직접 하드코딩하지 않고 Kustomize `images` 기능으로 분리합니다.

```yaml
images:
  - name: harbor.nplan.local:80/neuroplan/frontend
    newTag: "<tag>"
```

이를 통해 공통 Base Deployment를 변경하지 않고 환경별 배포 Image 버전을 관리할 수 있습니다.

구조:

```text
base/10-workloads.yaml
       │
       │ Image Repository
       ▼
onprem/kustomization.yaml
       │
       │ newTag
       ▼
Final Rendered Deployment
```

---

# Argo CD Main Application 연동

On-Prem Kustomize 경로는 Main Argo CD Application의 GitOps Source로 사용됩니다.

Application:

```text
neuroplan-login-mvp
```

GitOps Path:

```text
neuroplan-login-mvp/k8s/onprem
```

Repository:

```text
Infrastructure-hybrid09/onprem-k8s-application-devopsVM
```

Jenkins가 Image Tag를 변경하여 `main` Branch에 Push하면 Argo CD는 해당 변경사항을 Desired State 변경으로 감지합니다.

```text
Jenkins Commit
      │
      ▼
GitHub main
      │
      ▼
Argo CD
neuroplan-login-mvp
      │
      ▼
k8s/onprem
      │
      ▼
Main Kubernetes
```

---

# On-Prem 배포 흐름

```text
Git
neuroplan-login-mvp/k8s/onprem
        │
        ▼
Argo CD
neuroplan-login-mvp
        │
        ▼
Kustomize Render
        │
        ├── ../base
        ├── 20-httproute.yaml
        └── images.newTag
        │
        ▼
Main Kubernetes
        │
        ▼
application Namespace
        │
        ├── neuroplan-frontend x2
        ├── neuroplan-backend x2
        ├── Services
        ├── PDB
        └── HTTPRoute
```

---

## 수동 Kustomize Rendering 검증

Repository Root에서 다음 명령으로 최종 On-Prem Manifest를 확인할 수 있습니다.

```bash
kubectl kustomize neuroplan-login-mvp/k8s/onprem
```

파일로 저장:

```bash
kubectl kustomize neuroplan-login-mvp/k8s/onprem \
  > /tmp/neuroplan-onprem-rendered.yaml
```

적용될 Image 확인:

```bash
kubectl kustomize neuroplan-login-mvp/k8s/onprem \
  | grep 'image:'
```

예상 형태:

```text
image: harbor.nplan.local:80/neuroplan/frontend:<frontend-tag>
image: harbor.nplan.local:80/neuroplan/backend:<backend-tag>
```

---

# 실제 클러스터 검증

Main Kubernetes에 배포된 후 다음 명령으로 확인할 수 있습니다.

## Pod

```bash
kubectl get pods -n application
```

## Deployment

```bash
kubectl get deployment \
  neuroplan-frontend \
  neuroplan-backend \
  -n application
```

Base 정책이 유지되므로 정상 상태에서는 각 Deployment의 Replica 구성을 확인할 수 있습니다.

```text
Frontend : 2
Backend  : 2
```

---

## 실제 Image 확인

```bash
kubectl get deployment \
  neuroplan-frontend \
  neuroplan-backend \
  -n application \
  -o jsonpath='{range .items[*]}{.metadata.name}{" : "}{.spec.template.spec.containers[0].image}{"\n"}{end}'
```

결과는 `kustomization.yaml`의 `newTag`와 일치해야 합니다.

---

## HTTPRoute 확인

```bash
kubectl get httproute -n application
```

상세 확인:

```bash
kubectl describe httproute \
  neuroplan-login-mvp \
  -n application
```

`neuroplan-gateway`에 정상적으로 연결되었는지 Route 상태를 함께 확인합니다.

---

## Argo CD 확인

```bash
kubectl get application \
  neuroplan-login-mvp \
  -n argocd
```

운영 배포 확인 시 다음 상태를 확인합니다.

```text
SYNC   : Synced
HEALTH : Healthy
```

---

# Image Tag 수동 수정 주의

`kustomization.yaml`의 `newTag`는 Jenkins Pipeline에서 자동 관리합니다.

```yaml
images:
  - name: harbor.nplan.local:80/neuroplan/frontend
    newTag: "..."

  - name: harbor.nplan.local:80/neuroplan/backend
    newTag: "..."
```

따라서 일반적인 애플리케이션 배포에서는 이 값을 직접 수정하지 않는 것을 기준으로 합니다.

수동 수정 후 해당 Component의 Source가 변경되어 Jenkins가 다시 실행되면 Jenkins는 새로운 Git SHA를 기준으로 `newTag`를 다시 갱신합니다.

정상 배포 흐름:

```text
Source 변경
      │
      ▼
Jenkins
      │
      ▼
Harbor Image 생성
      │
      ▼
newTag 자동 변경
      │
      ▼
Git Commit / Push
      │
      ▼
Argo CD Sync
```

---

# 배포 실패 방지 관점

Jenkins는 Image Tag 변경 후 `kubectl kustomize` Rendering 검증을 수행합니다.

따라서 최소한 Kustomize Render 단계에서 새 Tag가 정상적으로 최종 Manifest에 반영되는지 확인한 뒤 Git Commit을 진행합니다.

```text
newTag 변경
     │
     ▼
Kustomize Render
     │
     ▼
Image Tag 확인
     │
     ├── 성공 → Git Commit / Push
     │
     └── 실패 → Pipeline 실패
```

실제 Kubernetes 적용 이후의 Rollout 상태와 Readiness는 Argo CD 및 Kubernetes에서 별도로 확인합니다.

---

# Main / DR 공통 Image 배포

Jenkins Pipeline은 새 Application Image를 Build하면 On-Prem과 DR의 동일 Component Tag를 같이 갱신합니다.

```text
              Jenkins
                 │
         Harbor Image Push
                 │
            <Git SHA>
                 │
        ┌────────┴────────┐
        ▼                 ▼
   Main On-Prem          DR
kustomization.yaml  kustomization.yaml
        │                 │
        ▼                 ▼
     Argo CD           Argo CD
        │                 │
        ▼                 ▼
 Main Kubernetes       DR k3s
```

이 방식으로 하나의 CI Pipeline 결과를 Main과 DR GitOps 배포 경로에 동일하게 반영합니다.

---

# 현재 On-Prem 구성 요약

```text
Main Kubernetes
        │
        ▼
Namespace
application
        │
        ├── Frontend Deployment
        │      └── replicas: 2
        │
        ├── Backend Deployment
        │      └── replicas: 2
        │
        ├── Frontend Service :80
        ├── Backend Service :8080
        │
        ├── Frontend PDB
        │      └── minAvailable: 1
        │
        ├── Backend PDB
        │      └── minAvailable: 1
        │
        └── HTTPRoute
               │
               ├── /api
               │    └── Backend :8080
               │
               └── /
                    └── Frontend :80
```

---

# 전체 CI/CD 구성 요약

```text
Developer
   │
   │ Source Change
   ▼
GitHub
   │
   ▼
Jenkins
   │
   ├── Detect Frontend / Backend Changes
   │
   ├── Generate 7-digit Git SHA
   │
   ├── Docker Build
   │
   ├── Harbor Push
   │
   ├── Update On-Prem newTag
   │
   ├── Update DR newTag
   │
   ├── Kustomize Validation
   │
   └── Git Commit / Push
   │
   ▼
GitHub main
   │
   ▼
Argo CD
neuroplan-login-mvp
   │
   ▼
Kustomize
k8s/onprem
   │
   ▼
Main Kubernetes
   │
   ▼
Rolling Deployment
```

---

## 관련 코드

- [Repository Jenkinsfile](../../../Jenkinsfile)
- [Base Kustomize](../base/)
- [DR Kustomize](../dr/)
- [On-Prem HTTPRoute](20-httproute.yaml)
- [On-Prem Kustomization](kustomization.yaml)
- [Ansible Repository - Argo CD Role](https://github.com/Infrastructure-hybrid09/onprem-k8s-ansible/tree/main/roles/argocd)

---

## 핵심 정리

이 디렉터리에서 가장 중요한 역할은 **Main Kubernetes의 GitOps Desired State를 정의하는 것**입니다.

```text
../base
→ 공통 Application Resource

20-httproute.yaml
→ On-Prem Traffic Routing

kustomization.yaml
→ On-Prem Resource 조합
→ Harbor Image Tag 지정

Jenkins
→ kustomization.yaml newTag 자동 변경

Argo CD
→ Git의 변경된 Desired State를 Main Kubernetes에 반영
```

따라서 On-Prem 애플리케이션 Image 배포는 다음 순서를 기준으로 자동화되어 있습니다.

```text
Source Code
→ Jenkins
→ Harbor
→ Kustomize newTag
→ Git
→ Argo CD
→ Main Kubernetes
```
