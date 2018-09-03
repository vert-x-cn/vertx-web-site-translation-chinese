# vertx-web-site-translation-chinese

# vert.x官网翻译项目 

##构建

```bash
mvn clean install
```

因为所有项目都在一起，而`vertx-web-site-translation-chinese`项目需要解包 stack目录下每个子模块的docs.zip包。因此需要执行install将各模块安装到本地maven仓库之后，再从本地加载到指定模块，并解包，然后执行其他任务。

##翻译

每个模块的翻译，需要到指定模块下的`src/main/translation`目录完成。git不管理空文件夹，因此，未翻译的模块，该目录在clone之后，理论上是不存在的，在构建之后，会自动创建。也可以手动创建。

###说明

vertx-core有个override需要翻译，翻译的文件在`src/main/override`目录完成

gulp有个task，源文件为`vertx-web-site-translation-chinese`模块的`src/main/tasks/generate-distribution-info.js`文件，里面有个http请求，因为官网没有发布3.6.0-SNPASHOT，故我在本地创建了几个对应版本号的文件，分别为
>vert.x-3.6.0-SNAPSHOT.tar.gz

>vert.x-3.6.0-SNAPSHOT.zip

>vert.x-3.6.0-SNAPSHOT-full.tar.gz

>vert.x-3.6.0-SNAPSHOT-full.zip

>vertx-docs-3.6.0-SNAPSHOT-html.zip

需要构建成功，需要在本地搭建http服务，并添加对应文件使gulp请求能通过

