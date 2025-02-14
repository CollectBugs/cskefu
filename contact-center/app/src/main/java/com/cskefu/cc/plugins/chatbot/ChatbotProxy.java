/* 
 * Copyright (C) 2023 Beijing Huaxia Chunsong Technology Co., Ltd. 
 * <https://www.chatopera.com>, Licensed under the Chunsong Public 
 * License, Version 1.0  (the "License"), https://docs.cskefu.com/licenses/v1.html
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * Copyright (C) 2019-Jun. 2023 Chatopera Inc, <https://www.chatopera.com>, 
 * Licensed under the Apache License, Version 2.0, 
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.cskefu.cc.plugins.chatbot;

import com.cskefu.cc.activemq.BrokerPublisher;
import com.cskefu.cc.basic.Constants;
import com.cskefu.cc.basic.MainContext;
import com.cskefu.cc.cache.Cache;
import com.cskefu.cc.model.Chatbot;
import com.cskefu.cc.persistence.repository.ChatMessageRepository;
import com.cskefu.cc.persistence.repository.ChatbotRepository;
import com.cskefu.cc.socketio.client.NettyClients;
import com.cskefu.cc.socketio.message.ChatMessage;
import com.cskefu.cc.socketio.message.Message;
import com.cskefu.cc.util.SerializeUtil;
import com.cskefu.cc.util.SystemEnvHelper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ChatbotProxy {
    private final static Logger logger = LoggerFactory.getLogger(ChatbotProxy.class);

    private final static String botServiceProvider = SystemEnvHelper.getenv(
            ChatbotConstants.BOT_PROVIDER, ChatbotConstants.DEFAULT_BOT_PROVIDER);

    @Autowired
    private BrokerPublisher brokerPublisher;

    @Autowired
    private ChatbotRepository chatbotRes;

    @Autowired
    private ChatMessageRepository chatMessageRes;

    @Autowired
    private Cache cache;

    /**
     * publish Message into ActiveMQ
     *
     * @param data
     * @param eventType
     */
    public void publishMessage(final ChatMessage data, final String eventType) {
        logger.info("[publishMessage] eventType {}", eventType);
        brokerPublisher.send(Constants.INSTANT_MESSAGING_MQ_QUEUE_CHATBOT, SerializeUtil.serialize(data));
    }

    /**
     * 使用chatbotID得到snsid
     *
     * @param chatbotID
     * @return
     */
    public static String resolveSnsidWithChatbotID(String chatbotID, String clientId) {
        return StringUtils.remove(chatbotID, clientId.toLowerCase() + "_");
    }

    /**
     * @param data
     * @param direction
     * @return
     */
    public Message createMessage(ChatMessage data, String direction) {
        if (!cache.findOneAgentUserByUserId(data.getUserid()).isPresent()) {
            return null;
        }

        // 设置发送消息体
        Message outMessage = new Message();
        outMessage.setMessage(data.getMessage());
        outMessage.setMessageType(data.getMsgtype());
        outMessage.setCalltype(direction);
        outMessage.setAgentUser(null);
        outMessage.setSnsAccount(null);
        if (StringUtils.isNotBlank(data.getSuggestmsg())) {
            outMessage.setSuggest(data.getSuggest());
        }

        outMessage.setContextid(data.getContextid());
        // FIXME 设置onlineUserName等信息
//        outMessage.setFromUser(data.getUserid());
//        outMessage.setToUser(data.getTouser());
        outMessage.setChannelMessage(data);
        outMessage.setCreatetime(Constants.DISPLAY_DATE_FORMATTER.format(data.getCreatetime()));

        /**
         * 保存消息
         */
        chatMessageRes.save(data);

        //将消息发送给 访客
        NettyClients.getInstance().sendChatbotEventMessage(
                data.getUserid(), MainContext.MessageType.MESSAGE.toString(), data);

        return outMessage;
    }


    /**
     * 发送聊天机器人消息
     *
     * @param data
     * @param appid
     * @param channel
     * @param direction
     * @param chatype
     * @param msgtype
     * @param userid
     * @return
     */
    public Message createMessage(
            final ChatMessage data,
            final String appid,
            final String channel,
            final String direction,
            final String chatype,
            final String msgtype,
            final String userid) {
        final Chatbot c = chatbotRes.findBySnsAccountIdentifier(appid);
        if (c == null) // ignore event if chatbot not exist.
        {
            return null;
        }

        data.setAiid(c.getId());
        data.setUserid(userid);
        data.setAgentserviceid(data.getContextid());
        data.setChatype(chatype);
        data.setChannel(channel);
        data.setMsgtype(msgtype);
        data.setUsession(data.getUserid());                //agentUser作为 session id
        data.setCalltype(direction);
        data.setUpdatetime(System.currentTimeMillis());
        return createMessage(data, direction);
    }

    /**
     * 发送文字消息
     *
     * @param data
     * @param direction
     */
    public void createTextMessage(final ChatMessage data, final String direction) {
        data.setMsgtype(MainContext.MediaType.TEXT.toString());
        createMessage(data, direction);
    }

    /**
     * 保存到数据库，发送到ChatMessage
     *
     * @param resp
     */
    public void saveAndPublish(final ChatMessage resp) {
        NettyClients.getInstance().sendChatbotEventMessage(
                resp.getUserid(), MainContext.MessageType.MESSAGE.toString(), resp);
        chatMessageRes.save(resp);
    }
}
