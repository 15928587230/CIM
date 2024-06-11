## 项目介绍

CIM是一套完善的消息推送框架，可应用于信令推送，即时聊天，移动设备指令推送等领域。开发者可沉浸于业务开发，不用关心消息通道长连接、消息编解码协议等繁杂处理。

---

## WEB聊天室
该项目是完全开源基于cim开发的一款web匿名聊天室，支持发送表情、图片、文字聊天，供学习使用
<div align="center">
   <img src="https://staticres.oss-cn-hangzhou.aliyuncs.com/chat-room/chat_window.png" width="45%"  />
   <img src="https://staticres.oss-cn-hangzhou.aliyuncs.com/chat-room/room_members.png" width="45%"  />
</div>

---  

## 功能预览

1.控制台页面[http://127.0.0.1:8080](http://127.0.0.1:8080)
![image](https://images.gitee.com/uploads/images/2019/0315/165050_9e269c1c_58912.png)

2.Android客户端
![image](https://images.gitee.com/uploads/images/2019/0315/165050_6f20f69e_58912.png)

3.Web客户端
![image](https://images.gitee.com/uploads/images/2019/0315/165050_dfc33c18_58912.png)

## Maven Gradle

服务端sdk引用

```

<dependency>
   <groupId>com.farsunset</groupId>
   <artifactId>cim-server-sdk-netty</artifactId>
   <version>4.2.6</version>
</dependency>

```

android端sdk引用

```
    implementation "com.farsunset:cim-android-sdk:4.2.10"
```