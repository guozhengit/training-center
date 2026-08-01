# 静态资源管理策略

`training-ui/dist/` 和 `training-web/src/main/resources/static/` 都属于前端构建产物，不作为源码长期维护。

## 推荐流程

开发阶段：

```powershell
cd D:\AI-SOURCE\jiupainews\training-center\training-ui
npm run dev
```

生产构建：

```powershell
cd D:\AI-SOURCE\jiupainews\training-center
npm --prefix training-ui run build
& 'D:\Program Files (x86)\apache-maven-3.9.9\bin\mvn.cmd' -pl training-web -am -Pfrontend -DskipTests package
```

`-Pfrontend` 会把 `training-ui/dist` 复制到 `training-web/src/main/resources/static`，随后打进 Spring Boot JAR。

## Git 策略

- 提交 Vue 源码：`training-ui/src/**`、`training-ui/package.json`、`training-ui/package-lock.json`。
- 不提交构建产物：`training-ui/dist/**`、`training-web/src/main/resources/static/**`。
- 如果需要发布离线制品，优先保存构建好的 JAR 或镜像，而不是把 hashed JS/CSS 提交到源码仓库。

这样能避免 hashed 资源频繁制造无意义 diff，也能确保前端资源始终由源码和 lockfile 可重复生成。
