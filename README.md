# vertx-web-site-translation-chinese

# vert.x官网翻译项目 

## 构建

maven虽然配置了自动执行npm，但有时会抽风，所以先手动把node的依赖安装，再执行maven就ok了
```bash
cd vertx-web-site-translation-chinese
npm install
mvn clean compile
```

## 预览

执行gulp的watch任务
```bash
gulp watch
```
然后打开`http://localhost:4000`即可预览

vertx-web-site-translation-chinese依赖vertx-translation-stack的所有子模块，在所有子模块处理完之后，会到子模块的target/asciidoc目录下拷贝处理完的文件到vertx-web-site-translation-chinese的target/docs目录下，然后进行site操作

## 翻译

每个模块的翻译，需要到指定模块下的`src/main/translation`目录完成。git不管理空文件夹，因此，未翻译的模块，该目录在clone之后，理论上是不存在的，在构建之后，会自动创建。也可以手动创建。

需要翻译的文件，通常在target/sources目录下的asciidoc目录中。部分模块(vertx-lang-kotlin-coroutines，vertx-service-proxy等模块)是直接解压到target/asciidoc的。

执行构建任务之后，可以拷贝上文说的文件来进行翻译

### vertx-core

vertx-core有个override需要翻译，翻译的文件在`src/main/override`目录完成

### 说明

gulp有个task，源文件为`vertx-web-site-translation-chinese`模块的`src/main/tasks/generate-distribution-info.js`文件，里面有个http请求，因为官网没有发布3.6.0-SNPASHOT，故我在本地创建了几个对应版本号的文件，分别为
>vert.x-3.6.0-SNAPSHOT.tar.gz

>vert.x-3.6.0-SNAPSHOT.zip

>vert.x-3.6.0-SNAPSHOT-full.tar.gz

>vert.x-3.6.0-SNAPSHOT-full.zip

>vertx-docs-3.6.0-SNAPSHOT-html.zip

需要构建成功，需要在本地搭建http服务，并添加对应文件使gulp请求能通过

### 当前翻译版本

目前在逐步将[VertxChina](https://github.com/VertxChina/vertx-translation-chinese)里的翻译复制到这里来

翻译文档的版本为3.4.2，而当前项目版本为3.6.0，因此可能会有部分差错
