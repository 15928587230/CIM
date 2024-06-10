/*
 * Copyright 2013-2022 Xia Jun(3979434@qq.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ***************************************************************************************
 *                                                                                     *
 *                        Website : http://www.farsunset.com                           *
 *                                                                                     *
 ***************************************************************************************
 */
package com.farsunset.cim.component.handler;

import com.farsunset.cim.component.handler.annotation.CIMHandler;
import com.farsunset.cim.component.redis.SignalRedisTemplate;
import com.farsunset.cim.constant.ChannelAttr;
import com.farsunset.cim.constants.Constants;
import com.farsunset.cim.entity.Session;
import com.farsunset.cim.group.SessionGroup;
import com.farsunset.cim.handler.CIMRequestHandler;
import com.farsunset.cim.model.ReplyBody;
import com.farsunset.cim.model.SentBody;
import com.farsunset.cim.service.SessionService;
import io.netty.channel.Channel;
import org.springframework.http.HttpStatus;

import javax.annotation.Resource;

/**
 * 客户长连接 账户绑定实现
 */
@CIMHandler(key = "client_bind")
public class BindHandler implements CIMRequestHandler {

	@Resource
	private SessionService sessionService;

	@Resource
	private SessionGroup sessionGroup;

	@Resource
	private SignalRedisTemplate signalRedisTemplate;


	/**
	 * 处理连接事件
	 * 		uid 第一次连接：
	 * 			保存会话Session信息到数据库
	 * 			设置channel基本属性
	 * 			保存 uid -> channel 映射关系到本地内存中
	 * 			发布 uid 上线事件，通知其他客户端下线
	 * 		uid 多次上来连接、比如多个设备连接：(多个浏览器同时连接，或者单个ios、android单独连接，因此发布事件下线其它登录设备)
	 * 			注意：多个浏览器是不用下线相同uid的连接，但是ios、android这种设备只有有一个人登录，具体看BindMessageListener
	 * 			sessionGroup.isManaged 如果存在 uid和channel的映射，直接返回，不存在执行后面
	 * 			保存uid新的会话session信息到数据库
	 * 			设置channel基本属性
	 *			保存 uid -> channel 映射关系到本地内存中
	 * 	 		发布 uid 上线事件，通知其他客户端下线
	 */
	@Override
	public void process(Channel channel, SentBody body) {

		if (sessionGroup.isManaged(channel)){
			return;
		}

		ReplyBody reply = new ReplyBody();
		reply.setKey(body.getKey());
		reply.setCode(HttpStatus.OK.value());
		reply.setTimestamp(System.currentTimeMillis());

		String uid = body.get("uid");
		Session session = new Session();
		session.setUid(uid);
		session.setNid(channel.attr(ChannelAttr.ID).get());
		session.setDeviceId(body.get("deviceId"));
		session.setChannel(body.get("channel"));
		session.setDeviceName(body.get("deviceName"));
		session.setAppVersion(body.get("appVersion"));
		session.setOsVersion(body.get("osVersion"));
		session.setLanguage(body.get("language"));

		channel.attr(ChannelAttr.UID).set(uid);
		channel.attr(ChannelAttr.CHANNEL).set(session.getChannel());
		channel.attr(ChannelAttr.DEVICE_ID).set(session.getDeviceId());
		channel.attr(ChannelAttr.LANGUAGE).set(session.getLanguage());

		/*
		 * 存储到数据库
		 */
		sessionService.add(session);

		channel.attr(Constants.SESSION_ID).set(session.getId());

		/*
		 * 添加到内存管理
		 */
		sessionGroup.add(channel);

		/*
		 * 向客户端发送bind响应
		 */
		channel.writeAndFlush(reply);

		/*
		 * 发送上线事件到集群中的其他实例，控制其他设备下线
		 */
		signalRedisTemplate.bind(session);
	}
}
