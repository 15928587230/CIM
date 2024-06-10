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
package com.farsunset.cim.component.message;

import com.farsunset.cim.component.event.SessionEvent;
import com.farsunset.cim.constant.ChannelAttr;
import com.farsunset.cim.entity.Session;
import com.farsunset.cim.group.SessionGroup;
import com.farsunset.cim.model.Message;
import com.farsunset.cim.util.JSONUtils;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOutboundInvoker;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 集群环境下，监控多设备登录情况，控制是否其余终端下线的逻辑
 */
@Component
public class BindMessageListener implements MessageListener {

    private static final String FORCE_OFFLINE_ACTION = "999";

    private static final String SYSTEM_ID = "0";

    /*
     一个账号只能在同一个类型的终端登录
     如: 多个android或ios不能同时在线
         一个android或ios可以和web，桌面同时在线
     */
    private final Map<String,String[]> conflictMap = new HashMap<>();

    /*
     * web可能同一个终端 打开多 tab页面，可以同时保持连接
     */
    private final Set<String> keepLiveChannels = new HashSet<>();

    @Resource
    private SessionGroup sessionGroup;

    public BindMessageListener(){
        conflictMap.put(Session.CHANNEL_ANDROID,new String[]{Session.CHANNEL_ANDROID,Session.CHANNEL_IOS});
        conflictMap.put(Session.CHANNEL_IOS,new String[]{Session.CHANNEL_ANDROID,Session.CHANNEL_IOS});
        conflictMap.put(Session.CHANNEL_WINDOWS,new String[]{Session.CHANNEL_WINDOWS,Session.CHANNEL_WEB,Session.CHANNEL_MAC});
        conflictMap.put(Session.CHANNEL_WEB,new String[]{Session.CHANNEL_WINDOWS,Session.CHANNEL_WEB,Session.CHANNEL_MAC});
        conflictMap.put(Session.CHANNEL_MAC,new String[]{Session.CHANNEL_WINDOWS,Session.CHANNEL_WEB,Session.CHANNEL_MAC});

        keepLiveChannels.add(Session.CHANNEL_WEB);
    }

    @EventListener
    public void onMessage(SessionEvent event) {
        this.handle(event.getSource());
    }

    @Override
    public void onMessage(org.springframework.data.redis.connection.Message redisMessage, byte[] bytes) {

        Session session = JSONUtils.fromJson(redisMessage.getBody(), Session.class);

        this.handle(session);
    }

    /**
     * 并发连接处理
     */
    private void handle(Session session){

        String uid = session.getUid();

        String[] conflictChannels = conflictMap.get(session.getChannel());

        if (ArrayUtils.isEmpty(conflictChannels)){
            return;
        }

        /**
         *  找出和当前uid相同类型相同的channel
         *  比如ios连接过了，又是ios连接，那就把之前的ios连接的channel找出来
         */
        Collection<Channel> channelList = sessionGroup.find(uid,conflictChannels);

        /**
         *  控制可以并发连接，比如都是web连接，则移除不做任何处理
         */
        channelList.removeIf(new KeepLivePredicate(session));

        // 关闭会触发closeFuture里面的监听器，清除内存channel同时触发inactive发布事件给closeHandler清理session
        /**
         *  比如第二次ios连接，也是用的同一手机，就关闭之前的连接，创建新的连接
         */
        channelList.stream().filter(new SameDevicePredicate(session)).forEach(ChannelOutboundInvoker::close);

        /**
         *  比如第二次ios连接，但是其它设备上连接过了，断开其它连接并通知设备下线消息
         */
        channelList.stream().filter(new DifferentDevicePredicate(session)).forEach(new BreakOffMessageConsumer(uid,session.getDeviceName()));
    }


    private static class BreakOffMessageConsumer implements Consumer<Channel> {

        private final Message message;

        private BreakOffMessageConsumer(String uid,String deviceName) {
            message = new Message();
            message.setAction(FORCE_OFFLINE_ACTION);
            message.setReceiver(uid);
            message.setSender(SYSTEM_ID);
            message.setContent(deviceName);
        }

        /**
         *  这里发送设备强制下线给客户端，并注册close监听器，客户端需要调用关闭连接方法
         *  channel.close之后会触发unregister和inactive方法，inactive做了哪些看CIM-Server inactive
         *  CIM-Server端会调用CloseHandler进行关闭channel操作
         *  这里CLOSE监听器，channel.close会触发channel closeFuture里面的监听器remover(add时加入的)，remover清除内存的channel
         */
        @Override
        public void accept(Channel channel) {
            channel.writeAndFlush(message).addListener(ChannelFutureListener.CLOSE);
        }
    }
    private static class SameDevicePredicate implements Predicate<Channel> {

        private final String deviceId;

        private SameDevicePredicate(Session session) {
            this.deviceId = session.getDeviceId();
        }

        @Override
        public boolean test(Channel channel) {
            return Objects.equals(this.deviceId,channel.attr(ChannelAttr.DEVICE_ID).get());
        }
    }

    private static class DifferentDevicePredicate implements Predicate<Channel>{

        private final SameDevicePredicate predicate;

        private DifferentDevicePredicate(Session session) {
            this.predicate = new SameDevicePredicate(session);
        }

        @Override
        public boolean test(Channel channel) {
            return !predicate.test(channel);
        }
    }


    private class KeepLivePredicate implements Predicate<Channel>{
        private final Session session;

        private KeepLivePredicate(Session session) {
            this.session = session;
        }

        @Override
        public boolean test(Channel ioChannel) {

            if (Objects.equals(session.getNid(),ioChannel.attr(ChannelAttr.ID).get())){
                return true;
            }

            String deviceId = ioChannel.attr(ChannelAttr.DEVICE_ID).toString();

            String channel = ioChannel.attr(ChannelAttr.CHANNEL).toString();

            return keepLiveChannels.contains(channel) && Objects.equals(session.getDeviceId(),deviceId);
        }
    }
}
