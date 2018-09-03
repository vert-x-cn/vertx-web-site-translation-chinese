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


