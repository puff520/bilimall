package cn.lili.modules.goods.serviceimpl;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.json.JSONUtil;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.properties.RocketmqCustomProperties;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.utils.BeanUtil;
import cn.lili.common.utils.DateUtil;
import cn.lili.common.vo.PageVO;
import cn.lili.modules.goods.entity.dos.Goods;
import cn.lili.modules.goods.entity.dos.Studio;
import cn.lili.modules.goods.entity.dos.StudioCommodity;
import cn.lili.modules.goods.entity.enums.StudioStatusEnum;
import cn.lili.modules.goods.entity.vos.StudioVO;
import cn.lili.modules.goods.mapper.CommodityMapper;
import cn.lili.modules.goods.mapper.StudioMapper;
import cn.lili.modules.goods.service.GoodsService;
import cn.lili.modules.goods.service.StudioCommodityService;
import cn.lili.modules.goods.service.StudioService;
import cn.lili.modules.goods.util.WechatLivePlayerUtil;
import cn.lili.mybatis.util.PageUtil;
import cn.lili.trigger.enums.DelayTypeEnums;
import cn.lili.trigger.interfaces.TimeTrigger;
import cn.lili.trigger.message.BroadcastMessage;
import cn.lili.trigger.model.TimeExecuteConstant;
import cn.lili.trigger.model.TimeTriggerMsg;
import cn.lili.trigger.util.DelayQueueTools;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ?????????????????????????????????
 *
 * @author Bulbasaur
 * @since 2021/5/17 10:04 ??????
 */
@Service
public class StudioServiceImpl extends ServiceImpl<StudioMapper, Studio> implements StudioService {

    @Autowired
    private WechatLivePlayerUtil wechatLivePlayerUtil;
    @Autowired
    private StudioCommodityService studioCommodityService;
    @Resource
    private CommodityMapper commodityMapper;
    @Autowired
    private TimeTrigger timeTrigger;
    @Autowired
    private RocketmqCustomProperties rocketmqCustomProperties;
    @Autowired
    private GoodsService goodsService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean create(Studio studio) {
        studio.setStoreId(Objects.requireNonNull(UserContext.getCurrentUser()).getStoreId());
        //?????????????????????
        Map<String, String> roomMap = wechatLivePlayerUtil.create(studio);
        studio.setRoomId(Convert.toInt(roomMap.get("roomId")));
        studio.setQrCodeUrl(roomMap.get("qrcodeUrl"));
        studio.setStatus(StudioStatusEnum.NEW.name());
        //???????????????????????????????????????????????????????????????
        if (this.save(studio)) {
            //????????????????????????
            BroadcastMessage broadcastMessage = new BroadcastMessage(studio.getId(), StudioStatusEnum.START.name());
            TimeTriggerMsg timeTriggerMsg = new TimeTriggerMsg(TimeExecuteConstant.BROADCAST_EXECUTOR,
                    Long.parseLong(studio.getStartTime()) * 1000L,
                    broadcastMessage,
                    DelayQueueTools.wrapperUniqueKey(DelayTypeEnums.BROADCAST, studio.getId()),
                    rocketmqCustomProperties.getPromotionTopic());

            //???????????????????????????????????????
            this.timeTrigger.addDelay(timeTriggerMsg);

            //????????????????????????
            broadcastMessage = new BroadcastMessage(studio.getId(), StudioStatusEnum.END.name());
            timeTriggerMsg = new TimeTriggerMsg(TimeExecuteConstant.BROADCAST_EXECUTOR,
                    Long.parseLong(studio.getEndTime()) * 1000L, broadcastMessage,
                    DelayQueueTools.wrapperUniqueKey(DelayTypeEnums.BROADCAST, studio.getId()),
                    rocketmqCustomProperties.getPromotionTopic());
            //???????????????????????????????????????
            this.timeTrigger.addDelay(timeTriggerMsg);
        }
        return true;

    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean edit(Studio studio) {
        Studio oldStudio = this.getById(studio.getId());
        wechatLivePlayerUtil.editRoom(studio);
        if (this.updateById(studio)) {
            //????????????????????????
            //???????????????
            BroadcastMessage broadcastMessage = new BroadcastMessage(studio.getId(), StudioStatusEnum.START.name());
            this.timeTrigger.edit(
                    TimeExecuteConstant.BROADCAST_EXECUTOR,
                    broadcastMessage,
                    Long.parseLong(oldStudio.getStartTime()) * 1000L,
                    Long.parseLong(studio.getStartTime()) * 1000L,
                    DelayQueueTools.wrapperUniqueKey(DelayTypeEnums.BROADCAST, studio.getId()),
                    DateUtil.getDelayTime(Long.parseLong(studio.getStartTime())),
                    rocketmqCustomProperties.getPromotionTopic());

            //???????????????
            broadcastMessage = new BroadcastMessage(studio.getId(), StudioStatusEnum.END.name());
            this.timeTrigger.edit(
                    TimeExecuteConstant.BROADCAST_EXECUTOR,
                    broadcastMessage,
                    Long.parseLong(oldStudio.getEndTime()) * 1000L,
                    Long.parseLong(studio.getEndTime()) * 1000L,
                    DelayQueueTools.wrapperUniqueKey(DelayTypeEnums.BROADCAST, studio.getId()),
                    DateUtil.getDelayTime(Long.parseLong(studio.getEndTime())),
                    rocketmqCustomProperties.getPromotionTopic());
        }
        return true;
    }

    @Override
    public StudioVO getStudioVO(String id) {
        StudioVO studioVO = new StudioVO();
        Studio studio = this.getById(id);
        //?????????????????????
        BeanUtil.copyProperties(studio, studioVO);
        //???????????????????????????
        studioVO.setCommodityList(commodityMapper.getCommodityByRoomId(studioVO.getRoomId()));
        return studioVO;
    }

    @Override
    public String getLiveInfo(Integer roomId) {
        Studio studio = this.getByRoomId(roomId);
        //????????????????????????????????????????????????????????????????????????????????????????????????
        if (studio.getMediaUrl() != null) {
            return studio.getMediaUrl();
        } else {
            String mediaUrl = wechatLivePlayerUtil.getLiveInfo(roomId);
            studio.setMediaUrl(mediaUrl);
            this.save(studio);
            return mediaUrl;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean push(Integer roomId, Integer liveGoodsId, String storeId, String goodsId) {

        //????????????????????????????????????
        if (studioCommodityService.getOne(
                new LambdaQueryWrapper<StudioCommodity>().eq(StudioCommodity::getRoomId, roomId)
                        .eq(StudioCommodity::getGoodsId, liveGoodsId)) != null) {
            throw new ServiceException(ResultCode.STODIO_GOODS_EXIST_ERROR);
        }

        Goods goods = goodsService.getOne(new LambdaQueryWrapper<Goods>().eq(Goods::getId, goodsId).eq(Goods::getStoreId, storeId));
        if (goods == null) {
            throw new ServiceException(ResultCode.USER_AUTHORITY_ERROR);
        }

        //??????????????????????????????????????????????????????
        if (Boolean.TRUE.equals(wechatLivePlayerUtil.pushGoods(roomId, liveGoodsId))) {
            studioCommodityService.save(new StudioCommodity(roomId, liveGoodsId));
            //???????????????????????????
            Studio studio = this.getByRoomId(roomId);
            studio.setRoomGoodsNum(studio.getRoomGoodsNum() != null ? studio.getRoomGoodsNum() + 1 : 1);
            //???????????????????????????????????????????????????????????????
            if (studio.getRoomGoodsNum() < 3) {
                studio.setRoomGoodsList(JSONUtil.toJsonStr(commodityMapper.getSimpleCommodityByRoomId(roomId)));
            }
            return this.updateById(studio);
        }
        return false;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean goodsDeleteInRoom(Integer roomId, Integer goodsId, String storeId) {
        Goods goods = goodsService.getOne(new LambdaQueryWrapper<Goods>().eq(Goods::getId, goodsId).eq(Goods::getStoreId, storeId));
        if (goods == null) {
            throw new ServiceException(ResultCode.USER_AUTHORITY_ERROR);
        }
        //??????????????????????????????????????????????????????
        if (Boolean.TRUE.equals(wechatLivePlayerUtil.goodsDeleteInRoom(roomId, goodsId))) {
            studioCommodityService.remove(new QueryWrapper<StudioCommodity>().eq("room_id", roomId).eq("goods_id", goodsId));
            //???????????????????????????
            Studio studio = this.getByRoomId(roomId);
            studio.setRoomGoodsNum(studio.getRoomGoodsNum() - 1);
            //???????????????????????????????????????????????????????????????
            if (studio.getRoomGoodsNum() < 3) {
                studio.setRoomGoodsList(JSONUtil.toJsonStr(commodityMapper.getSimpleCommodityByRoomId(roomId)));
            }
            return this.updateById(studio);
        }
        return false;
    }

    @Override
    public IPage<StudioVO> studioList(PageVO pageVO, Integer recommend, String status) {
        QueryWrapper queryWrapper = new QueryWrapper<Studio>()
                .eq(recommend != null, "recommend", true)
                .eq(CharSequenceUtil.isNotEmpty(status), "status", status)
                .orderByDesc("create_time");
        if (UserContext.getCurrentUser() != null && UserContext.getCurrentUser().getRole().equals(UserEnums.STORE)) {
            queryWrapper.eq("store_id", UserContext.getCurrentUser().getStoreId());
        }
        Page page = this.page(PageUtil.initPage(pageVO), queryWrapper);
        List<Studio> records = page.getRecords();
        List<StudioVO> studioVOS = new ArrayList<>();
        for (Studio record : records) {
            StudioVO studioVO = new StudioVO();
            //?????????????????????
            BeanUtil.copyProperties(record, studioVO);
            //???????????????????????????
            studioVO.setCommodityList(commodityMapper.getCommodityByRoomId(studioVO.getRoomId()));
            studioVOS.add(studioVO);
        }
        page.setRecords(studioVOS);
        return page;

    }

    @Override
    public void updateStudioStatus(BroadcastMessage broadcastMessage) {
        this.update(new LambdaUpdateWrapper<Studio>()
                .eq(Studio::getId, broadcastMessage.getStudioId())
                .set(Studio::getStatus, broadcastMessage.getStatus()));
    }

    /**
     * ???????????????ID???????????????
     *
     * @param roomId ?????????ID
     * @return ?????????
     */
    private Studio getByRoomId(Integer roomId) {
        return this.getOne(new LambdaQueryWrapper<Studio>().eq(Studio::getRoomId, roomId));
    }
}
