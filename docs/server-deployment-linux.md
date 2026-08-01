# Training Center Linux 服务器部署手册

本文档面向服务器目录为 `/home/docker` 的部署场景。

默认项目路径：

```bash
/home/docker/training-center
```

默认运行数据路径：

```bash
/home/docker/training-data
```

## 1. 部署目标

将 `training-center` 部署为 Spring Boot Web 服务，提供训练中心可视化面板，支持题库浏览、机试训练、口述训练、项目答辩训练、历史记录与导出。

推荐部署形态：

```text
Spring Boot 后端 + Vue 静态资源 + SQLite 本地数据库
```

## 2. 服务器目录规划

建议目录：

```bash
/home/docker/
├── training-center/
├── training-data/
│   ├── database/
│   ├── submissions/
│   ├── exports/
│   └── logs/
└── start_training_center.sh
```

创建运行目录：

```bash
mkdir -p /home/docker/training-data/database
mkdir -p /home/docker/training-data/submissions
mkdir -p /home/docker/training-data/exports
mkdir -p /home/docker/training-data/logs
```

## 3. 环境要求

| 组件 | 推荐版本 | 用途 |
|---|---:|---|
| JDK | 17+ | 后端编译运行、Java 题判题 |
| Maven | 3.9+ | 后端构建 |
| Node.js | 20+ | Vue 前端构建 |
| Python | 3.10+ | Python 题判题 |

检查环境：

```bash
java -version
mvn -version
node -v
npm -v
python3 --version
```

安装示例：

```bash
# CentOS / Rocky / AlmaLinux
yum install -y java-17-openjdk java-17-openjdk-devel maven python3 python3-pip

# Ubuntu / Debian
apt update
apt install -y openjdk-17-jdk maven python3 python3-pip
```

Node.js 推荐使用 nvm 安装：

```bash
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/master/install.sh | bash
source ~/.bashrc
nvm install 20.17.0
nvm use 20.17.0
```

## 4. 检查项目

```bash
cd /home/docker/training-center
ls
```

应能看到：

```text
pom.xml
training-core
training-cli
training-web
training-ui
config
starters
docs
```

如果需要自动判题，还应确认工作区内有：

```bash
ls /home/docker/output/coding-ai-exam
```

## 5. 构建前端

```bash
cd /home/docker/training-center/training-ui
npm install
npm run build
```

可选测试：

```bash
npm run test:run
```

构建完成后应生成：

```bash
/home/docker/training-center/training-ui/dist
```

## 6. 构建后端

```bash
cd /home/docker/training-center
mvn test
mvn -pl training-web -am -Pfrontend -DskipTests package
```

检查产物：

```bash
ls training-web/target/*.jar
```

## 7. 手动启动验证

```bash
cd /home/docker/training-center

TRAINING_WORKSPACE=/home/docker \
TRAINING_DATABASE_PATH=/home/docker/training-data/database/training.db \
TRAINING_OUTPUT_ROOT=/home/docker/training-data \
SERVER_PORT=8080 \
java -jar training-web/target/training-web-*.jar
```

访问：

```text
http://服务器IP:8080
```

健康检查：

```bash
curl http://127.0.0.1:8080/api/health
```

期望看到：

```json
{
  "status": "UP"
}
```

## 8. 后台启动脚本

创建脚本：

```bash
vi /home/docker/start_training_center.sh
```

写入：

```bash
#!/bin/bash

APP_HOME=/home/docker/training-center
DATA_HOME=/home/docker/training-data
LOG_FILE=$DATA_HOME/logs/training-center.log

mkdir -p $DATA_HOME/database
mkdir -p $DATA_HOME/submissions
mkdir -p $DATA_HOME/exports
mkdir -p $DATA_HOME/logs

cd $APP_HOME || exit 1

export TRAINING_WORKSPACE=/home/docker
export TRAINING_DATABASE_PATH=$DATA_HOME/database/training.db
export TRAINING_OUTPUT_ROOT=$DATA_HOME
export SERVER_PORT=8080

JAR_FILE=$(ls $APP_HOME/training-web/target/training-web-*.jar | head -n 1)

if [ -z "$JAR_FILE" ]; then
  echo "未找到 training-web jar，请先执行 mvn -pl training-web -am -Pfrontend -DskipTests package"
  exit 1
fi

nohup java -jar "$JAR_FILE" > "$LOG_FILE" 2>&1 &

echo "training-center started."
echo "PID: $!"
echo "Log: $LOG_FILE"
```

授权并启动：

```bash
chmod +x /home/docker/start_training_center.sh
/home/docker/start_training_center.sh
```

查看日志：

```bash
tail -f /home/docker/training-data/logs/training-center.log
```

## 9. 停止和重启

查看进程：

```bash
ps -ef | grep training-web | grep -v grep
```

停止：

```bash
kill PID
```

重启：

```bash
/home/docker/start_training_center.sh
```

## 10. 更新部署

```bash
cd /home/docker/training-center
git pull

cd training-ui
npm install
npm run build

cd /home/docker/training-center
mvn -pl training-web -am -Pfrontend -DskipTests package

ps -ef | grep training-web | grep -v grep
kill PID

/home/docker/start_training_center.sh
```

## 11. 常见问题

### 11.1 外部打不开页面

先在服务器本机检查：

```bash
curl http://127.0.0.1:8080/api/health
ss -lntp | grep 8080
```

如果本机正常、外部打不开，通常是云安全组或防火墙未开放 8080。

CentOS 防火墙示例：

```bash
firewall-cmd --add-port=8080/tcp --permanent
firewall-cmd --reload
```

### 11.2 数据库无法创建

检查目录权限：

```bash
ls -ld /home/docker/training-data
ls -ld /home/docker/training-data/database
```

修复：

```bash
chmod -R 755 /home/docker/training-data
```

### 11.3 页面还是旧版本

重新构建前端并重新打包后端：

```bash
cd /home/docker/training-center/training-ui
npm run build

cd /home/docker/training-center
mvn -pl training-web -am -Pfrontend -DskipTests package
```

然后重启服务。

