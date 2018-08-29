# vertx-web-site-translation-chinese

vert.x官网翻译

目的是只翻译java版的adoc文件，其他语言使用docgen自动生成相应的文档，然后按[vertx-web-site](https://github.com/vert-x3/vertx-web-site)项目自动生成网站

生成好的网站放到github pages上

当然我英文差的很，所以这只是一个翻译项目，具体的翻译内容需要专业人士来做


# 文件说明
除了src/main/translation之外，其他文件均从[vertx-web-site](https://github.com/vert-x3/vertx-web-site)拷贝而来

translation目录下的一些目录会按vertx的规则来

# stack-generation

该目录完全遵照[vertx-stack-generation](https://github.com/vert-x3/vertx-stack-generation)项目来设计，所以该

所有翻译文档，必须是adoc文档(除非有额外配置md文件的插件将md转成vertx-web-site需要的html文件，如果配置了，则原来用来说明的readme.md等文件，需要改为其他后缀)

##polyglot

本目录下为对应语言的翻译文档，翻译的文档不参与docgen，所有源码也为对应语言的源码，不参与处理

##origin

本目录为源文档目录，会通过docgen生成对应语言的文档
