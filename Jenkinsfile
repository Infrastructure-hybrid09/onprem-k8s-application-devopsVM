pipeline {
    agent any

    options {
        skipDefaultCheckout(true)
        disableConcurrentBuilds()
    }

    environment {
        APP_DIR = 'neuroplan-login-mvp'

        FRONTEND_IMAGE = 'harbor.nplan.local:80/neuroplan/frontend'
        BACKEND_IMAGE  = 'harbor.nplan.local:80/neuroplan/backend'

        KUSTOMIZATION_ONPREM = 'neuroplan-login-mvp/k8s/onprem/kustomization.yaml'
        KUSTOMIZATION_DR     = 'neuroplan-login-mvp/k8s/dr/kustomization.yaml'

        APP_REPO_SSH = 'git@github.com:Infrastructure-hybrid09/onprem-k8s-application-devopsVM.git'
    }

    stages {

        stage('Checkout') {
            steps {
                script {
                    def scmVars = checkout scm

                    env.GIT_COMMIT_FULL = scmVars.GIT_COMMIT
                    env.GIT_PREVIOUS_COMMIT_RESOLVED =
                        scmVars.GIT_PREVIOUS_COMMIT ?: ''

                    env.IMAGE_TAG = sh(
                        script: 'git rev-parse --short=7 HEAD',
                        returnStdout: true
                    ).trim()

                    echo "Commit    : ${env.GIT_COMMIT_FULL}"
                    echo "Image Tag : ${env.IMAGE_TAG}"
                }
            }
        }


        stage('Detect Changes') {
            steps {
                script {
                    def changedFiles = sh(
                        script: '''
                            set -eu

                            PREV="${GIT_PREVIOUS_COMMIT_RESOLVED:-}"

                            if [ -n "$PREV" ] && \
                               git cat-file -e "$PREV^{commit}" 2>/dev/null
                            then
                                git diff --name-only \
                                  "$PREV" \
                                  "$GIT_COMMIT_FULL"
                            else
                                git show \
                                  --pretty="" \
                                  --name-only \
                                  "$GIT_COMMIT_FULL"
                            fi
                        ''',
                        returnStdout: true
                    ).trim()

                    echo "Changed files:"
                    echo changedFiles ?: "(none)"

                    def files = changedFiles
                        ? changedFiles.split('\n')
                        : []

                    env.FRONTEND_CHANGED = files.any {
                        it.startsWith(
                            'neuroplan-login-mvp/frontend/'
                        )
                    }.toString()

                    env.BACKEND_CHANGED = files.any {
                        it.startsWith(
                            'neuroplan-login-mvp/backend/'
                        )
                    }.toString()

                    echo "Frontend changed : ${env.FRONTEND_CHANGED}"
                    echo "Backend changed  : ${env.BACKEND_CHANGED}"
                }
            }
        }


        stage('Harbor Login') {
            when {
                expression {
                    env.FRONTEND_CHANGED == 'true' ||
                    env.BACKEND_CHANGED == 'true'
                }
            }

            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'harbor-registry',
                        usernameVariable: 'HARBOR_USER',
                        passwordVariable: 'HARBOR_PASSWORD'
                    )
                ]) {
                    sh '''
                        set -eu

                        echo "$HARBOR_PASSWORD" | docker login harbor.nplan.local:80 -u "$HARBOR_USER" --password-stdin
                    '''
                }
            }
        }



        stage('Build Frontend') {
            when {
                expression {
                    env.FRONTEND_CHANGED == 'true'
                }
            }

            steps {
                sh '''
                    set -eu

                    docker build \
                      -f "$APP_DIR/frontend/Dockerfile" \
                      -t "$FRONTEND_IMAGE:$IMAGE_TAG" \
                      "$APP_DIR/frontend"
                '''
            }
        }


        stage('Push Frontend') {
            when {
                expression {
                    env.FRONTEND_CHANGED == 'true'
                }
            }

            steps {
                sh '''
                    set -eu

                    docker push \
                      "$FRONTEND_IMAGE:$IMAGE_TAG"
                '''
            }
        }


        stage('Build Backend') {
            when {
                expression {
                    env.BACKEND_CHANGED == 'true'
                }
            }

            steps {
                sh '''
                    set -eu

                    docker build \
                      -f "$APP_DIR/backend/Dockerfile" \
                      -t "$BACKEND_IMAGE:$IMAGE_TAG" \
                      "$APP_DIR/backend"
                '''
            }
        }


        stage('Push Backend') {
            when {
                expression {
                    env.BACKEND_CHANGED == 'true'
                }
            }

            steps {
                sh '''
                    set -eu

                    docker push \
                      "$BACKEND_IMAGE:$IMAGE_TAG"
                '''
            }
        }


        stage('Update Kustomize Image Tags') {
            when {
                expression {
                    env.FRONTEND_CHANGED == 'true' ||
                    env.BACKEND_CHANGED == 'true'
                }
            }

            steps {
                sh '''
                    set -eu

                    python3 <<'PY'
import os
from pathlib import Path

paths = [
    Path(os.environ["KUSTOMIZATION_ONPREM"]),
    Path(os.environ["KUSTOMIZATION_DR"]),
]

tag = os.environ["IMAGE_TAG"]

targets = []

if os.environ["FRONTEND_CHANGED"] == "true":
    targets.append(os.environ["FRONTEND_IMAGE"])

if os.environ["BACKEND_CHANGED"] == "true":
    targets.append(os.environ["BACKEND_IMAGE"])

for path in paths:
    lines = path.read_text().splitlines()

    for image in targets:
        found = False

        for i, line in enumerate(lines):
            if line.strip() == f"- name: {image}":

                for j in range(i + 1, min(i + 5, len(lines))):
                    if lines[j].strip().startswith("newTag:"):

                        indent = lines[j][
                            :len(lines[j]) - len(lines[j].lstrip())
                        ]

                        lines[j] = f'{indent}newTag: "{tag}"'
                        found = True
                        break

                break

        if not found:
            raise SystemExit(
                f"newTag entry not found for image: {image} in {path}"
            )

    path.write_text(chr(10).join(lines) + chr(10))
PY

                    echo "Updated On-Prem Kustomize:"
                    grep -A1 'name:' "$KUSTOMIZATION_ONPREM"

                    echo "Updated DR Kustomize:"
                    grep -A1 'name:' "$KUSTOMIZATION_DR"
                '''
            }
        }


        stage('Validate Kustomize') {
            when {
                expression {
                    env.FRONTEND_CHANGED == 'true' ||
                    env.BACKEND_CHANGED == 'true'
                }
            }

            steps {
                sh '''
                    set -eu

                    kubectl kustomize \
                      "$APP_DIR/k8s/onprem" \
                      > /tmp/neuroplan-onprem-rendered.yaml

                    kubectl kustomize \
                      "$APP_DIR/k8s/dr" \
                      > /tmp/neuroplan-dr-rendered.yaml

                    echo "On-Prem rendered images:"
                    grep 'image:' /tmp/neuroplan-onprem-rendered.yaml

                    echo "DR rendered images:"
                    grep 'image:' /tmp/neuroplan-dr-rendered.yaml

                    if [ "$FRONTEND_CHANGED" = "true" ]; then
                        grep -q \
                          "image: $FRONTEND_IMAGE:$IMAGE_TAG" \
                          /tmp/neuroplan-onprem-rendered.yaml

                        grep -q \
                          "image: $FRONTEND_IMAGE:$IMAGE_TAG" \
                          /tmp/neuroplan-dr-rendered.yaml
                    fi

                    if [ "$BACKEND_CHANGED" = "true" ]; then
                        grep -q \
                          "image: $BACKEND_IMAGE:$IMAGE_TAG" \
                          /tmp/neuroplan-onprem-rendered.yaml

                        grep -q \
                          "image: $BACKEND_IMAGE:$IMAGE_TAG" \
                          /tmp/neuroplan-dr-rendered.yaml
                    fi
                '''
            }
        }


        stage('Commit Kustomize Change') {
            when {
                expression {
                    env.FRONTEND_CHANGED == 'true' ||
                    env.BACKEND_CHANGED == 'true'
                }
            }

            steps {
                withCredentials([
                    sshUserPrivateKey(
                        credentialsId: 'app-repo-write-credentials',
                        keyFileVariable: 'APP_REPO_KEY',
                        usernameVariable: 'APP_REPO_USER'
                    )
                ]) {
                    sh '''
                        set -eu

                        if git diff --quiet -- \
                          "$KUSTOMIZATION_ONPREM" \
                          "$KUSTOMIZATION_DR"; then
                            echo "No Kustomize changes to commit."
                            exit 0
                        fi

                        git config user.name "Jenkins CI"
                        git config user.email "jenkins@nplan.local"

                        git add \
                          "$KUSTOMIZATION_ONPREM" \
                          "$KUSTOMIZATION_DR"

                        git commit \
                          -m "ci: update image tags to $IMAGE_TAG"

                        export GIT_SSH_COMMAND="ssh \
                          -i $APP_REPO_KEY \
                          -o IdentitiesOnly=yes \
                          -o StrictHostKeyChecking=accept-new"

                        git push \
                          "$APP_REPO_SSH" \
                          HEAD:main
                    '''
                }
            }
        }

    }


    post {
        success {
            echo "NeuroPlan CI pipeline completed successfully."
        }

        failure {
            echo "NeuroPlan CI pipeline failed."
        }

        always {
            sh '''
                rm -f /tmp/neuroplan-onprem-rendered.yaml /tmp/neuroplan-dr-rendered.yaml || true
            '''
        }
    }
}
