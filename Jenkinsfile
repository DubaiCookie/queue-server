pipeline {
    agent any

    environment {
        IMAGE_NAME = 'jinyoung1226/queue-server'
        DEPLOY_DIR = '/home/jinyoung/skala-mini/queue-server'
    }

    stages {

        stage('01. Git 소스코드 체크아웃') {
            steps {
                echo 'Git에서 코드 가져오기'
                checkout scm
            }
        }

        stage('02. Gradlew 실행 권한 부여') {
            steps {
                echo 'Gradlew 실행 권한 설정'
                sh 'chmod +x ./gradlew'
            }
        }

        stage('03. Gradle 빌드 (jar 생성)') {
            steps {
                echo 'Gradle 빌드 수행'
                sh './gradlew clean build'
            }
        }

        stage('04. Docker Hub 로그인') {
            steps {
                withCredentials([
                    usernamePassword(
                        credentialsId: 'docker-hub-credentials',
                        usernameVariable: 'DOCKER_USER',
                        passwordVariable: 'DOCKER_PASSWORD'
                    )
                ]) {
                    echo 'Docker Hub 로그인'
                    sh '''
                        echo "$DOCKER_PASSWORD" | docker login \
                          -u "$DOCKER_USER" \
                          --password-stdin
                    '''
                }
            }
        }

        stage('05. Docker 이미지 빌드') {
            steps {
                echo 'Docker 이미지 빌드'
                sh '''
                    docker build -t "$IMAGE_NAME" .
                '''
            }
        }

        stage('06. Docker 이미지 Push') {
            steps {
                echo 'Docker Hub에 이미지 Push'
                sh '''
                    docker push "$IMAGE_NAME"
                '''
            }
        }

        stage('07. Jenkins 서버 내 기존 이미지 정리') {
            steps {
                echo 'Jenkins 서버 내 기존 이미지 정리'
                sh '''
                    if [ "$(docker images -q "$IMAGE_NAME")" ]; then
                        docker rmi -f "$IMAGE_NAME"
                        echo "기존 이미지 삭제 완료"
                    else
                        echo "삭제할 이미지 없음"
                    fi
                '''
            }
        }

        stage('08. 배포 서버 컨테이너 중지 (SSH)') {
            steps {
                withCredentials([
                    sshUserPrivateKey(
                        credentialsId: 'deploy-server-ssh',
                        keyFileVariable: 'SSH_KEY',
                        usernameVariable: 'DEPLOY_USERNAME'
                    ),
                    string(credentialsId: 'deploy-server-ip', variable: 'DEPLOY_IP'),
                    string(credentialsId: 'deploy-server-port', variable: 'DEPLOY_PORT')
                ]) {
                    echo '원격 서버 docker-compose down'
                    sh '''
                    ssh -p "$DEPLOY_PORT" -i "$SSH_KEY" -o StrictHostKeyChecking=no \
                    "$DEPLOY_USERNAME@$DEPLOY_IP" \
                    "cd "$DEPLOY_DIR" && docker-compose down || true"
                    '''
                }
            }
        }

        stage('09. 배포 서버 미사용 이미지 정리') {
            steps {
                withCredentials([
                    sshUserPrivateKey(
                        credentialsId: 'deploy-server-ssh',
                        keyFileVariable: 'SSH_KEY',
                        usernameVariable: 'DEPLOY_USERNAME'
                    ),
                    string(credentialsId: 'deploy-server-ip', variable: 'DEPLOY_IP'),
                    string(credentialsId: 'deploy-server-port', variable: 'DEPLOY_PORT')
                ]) {
                    echo '배포 서버 이미지 prune'
                    sh '''
                    ssh -p "$DEPLOY_PORT" -i "$SSH_KEY" -o StrictHostKeyChecking=no \
                    "$DEPLOY_USERNAME@$DEPLOY_IP" \
                    "docker image prune -f"
                    '''
                }
            }
        }

        stage('10. 배포 서버에서 최신 이미지 Pull') {
            steps {
                withCredentials([
                    sshUserPrivateKey(
                        credentialsId: 'deploy-server-ssh',
                        keyFileVariable: 'SSH_KEY',
                        usernameVariable: 'DEPLOY_USERNAME'
                    ),
                    string(credentialsId: 'deploy-server-ip', variable: 'DEPLOY_IP'),
                    string(credentialsId: 'deploy-server-port', variable: 'DEPLOY_PORT')
                ]) {
                    echo '배포 서버 docker-compose pull'
                    sh '''
                    ssh -p "$DEPLOY_PORT" -i "$SSH_KEY" -o StrictHostKeyChecking=no \
                    "$DEPLOY_USERNAME@$DEPLOY_IP" \
                    "cd \"$DEPLOY_DIR\" && docker-compose pull"
                    '''
                }
            }
        }

        stage('11. 배포 서버 컨테이너 기동') {
            steps {
                withCredentials([
                    sshUserPrivateKey(
                        credentialsId: 'deploy-server-ssh',
                        keyFileVariable: 'SSH_KEY',
                        usernameVariable: 'DEPLOY_USERNAME'
                    ),
                    string(credentialsId: 'deploy-server-ip', variable: 'DEPLOY_IP'),
                    string(credentialsId: 'deploy-server-port', variable: 'DEPLOY_PORT')
                ]) {
                    echo '배포 서버 docker-compose up'
                    sh '''
                    ssh -p "$DEPLOY_PORT" -i "$SSH_KEY" -o StrictHostKeyChecking=no \
                    "$DEPLOY_USERNAME@$DEPLOY_IP" \
                    "cd \"$DEPLOY_DIR\" && docker-compose up -d"
                    '''
                }
            }
        }
    }
}