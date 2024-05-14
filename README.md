# 项目启动模版
## SpringBoot + MyBatisPlus + OceanBase + Redis + Satoken(权限认证)
本项目采用 SpringBoot3 与 OceanBase 4.2.3 进行个人搭建的模版，后端开发文档采用的是 Knife4j 的 Swagger 文档。<br>
后端开发文档地址为：<a herf="http://localhost:8391/doc.html">http://localhost:8391/doc.html </a><br>
启动项目之前先修改补充 application.xml 中`$$$`、`XXX`部分信息，以及数据库连接相关信息。<br>
默认超级管理员账号：admin 密码：123456<br>
非自增id采用的自定义改进版(缩短版)雪花算法，因为完整的雪花算法超过前端number的18位长度限制，会导致传回时丢失精度。<br>

> 为什么要使用 OceanBase？

1. 该数据库是阿里使用现代数据架构打造的开源分布式数据库，能完美兼容 MySQL 。
2. 该数据库已经运用于蚂蚁金融等领域，具有较高的安全性和可用性。
3. 支持国产！支持开源！

想要了解具体关于 OceanBase 数据库的部署过程或其他信息，可以访问[官网链接](https://www.oceanbase.com/product/opensource)进行查阅<br>
<font color="Orange">PS:由于是分布式数据库，所以 OceanBase 数据库在运行过程中平均需要 4.5g 的内存空间，请酌情考虑自身资源使用！</font>