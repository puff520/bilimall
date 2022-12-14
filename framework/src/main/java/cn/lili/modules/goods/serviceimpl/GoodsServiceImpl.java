package cn.lili.modules.goods.serviceimpl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.text.CharSequenceUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.json.JSONUtil;
import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.event.TransactionCommitSendMQEvent;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.properties.RocketmqCustomProperties;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.modules.goods.entity.dos.Category;
import cn.lili.modules.goods.entity.dos.Goods;
import cn.lili.modules.goods.entity.dos.GoodsGallery;
import cn.lili.modules.goods.entity.dos.Wholesale;
import cn.lili.modules.goods.entity.dto.GoodsOperationDTO;
import cn.lili.modules.goods.entity.dto.GoodsParamsDTO;
import cn.lili.modules.goods.entity.dto.GoodsSearchParams;
import cn.lili.modules.goods.entity.enums.GoodsAuthEnum;
import cn.lili.modules.goods.entity.enums.GoodsStatusEnum;
import cn.lili.modules.goods.entity.vos.GoodsSkuVO;
import cn.lili.modules.goods.entity.vos.GoodsVO;
import cn.lili.modules.goods.mapper.GoodsMapper;
import cn.lili.modules.goods.service.*;
import cn.lili.modules.member.entity.dos.MemberEvaluation;
import cn.lili.modules.member.entity.enums.EvaluationGradeEnum;
import cn.lili.modules.member.service.MemberEvaluationService;
import cn.lili.modules.store.entity.dos.FreightTemplate;
import cn.lili.modules.store.entity.dos.Store;
import cn.lili.modules.store.entity.vos.StoreVO;
import cn.lili.modules.store.service.FreightTemplateService;
import cn.lili.modules.store.service.StoreService;
import cn.lili.modules.system.entity.dos.Setting;
import cn.lili.modules.system.entity.dto.GoodsSetting;
import cn.lili.modules.system.entity.enums.SettingEnum;
import cn.lili.modules.system.service.SettingService;
import cn.lili.mybatis.util.PageUtil;
import cn.lili.rocketmq.RocketmqSendCallbackBuilder;
import cn.lili.rocketmq.tags.GoodsTagsEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * ?????????????????????
 *
 * @author pikachu
 * @since 2020-02-23 15:18:56
 */
@Service
public class GoodsServiceImpl extends ServiceImpl<GoodsMapper, Goods> implements GoodsService {


    /**
     * ??????
     */
    @Autowired
    private CategoryService categoryService;
    /**
     * ??????
     */
    @Autowired
    private SettingService settingService;
    /**
     * ????????????
     */
    @Autowired
    private GoodsGalleryService goodsGalleryService;
    /**
     * ????????????
     */
    @Autowired
    private GoodsSkuService goodsSkuService;
    /**
     * ????????????
     */
    @Autowired
    private StoreService storeService;
    /**
     * ????????????
     */
    @Autowired
    private MemberEvaluationService memberEvaluationService;
    /**
     * rocketMq
     */
    @Autowired
    private RocketMQTemplate rocketMQTemplate;
    /**
     * rocketMq??????
     */
    @Autowired
    private RocketmqCustomProperties rocketmqCustomProperties;


    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;
    @Autowired
    private FreightTemplateService freightTemplateService;

    @Autowired
    private WholesaleService wholesaleService;

    @Autowired
    private Cache<GoodsVO> cache;

    @Override
    public List<Goods> getByBrandIds(List<String> brandIds) {
        LambdaQueryWrapper<Goods> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.in(Goods::getBrandId, brandIds);
        return list(lambdaQueryWrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void underStoreGoods(String storeId) {
        //????????????ID??????
        List<String> list = this.baseMapper.getGoodsIdByStoreId(storeId);
        //????????????????????????
        updateGoodsMarketAble(list, GoodsStatusEnum.DOWN, "????????????");

        applicationEventPublisher.publishEvent(new TransactionCommitSendMQEvent("????????????",
                rocketmqCustomProperties.getGoodsTopic(), GoodsTagsEnum.DOWN.name(), JSONUtil.toJsonStr(list)));

    }

    /**
     * ??????????????????
     *
     * @param goodsId ??????id
     * @param params  ????????????
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateGoodsParams(String goodsId, String params) {
        LambdaUpdateWrapper<Goods> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(Goods::getId, goodsId);
        updateWrapper.set(Goods::getParams, params);
        this.update(updateWrapper);
    }

    @Override
    public final long getGoodsCountByCategory(String categoryId) {
        QueryWrapper<Goods> queryWrapper = Wrappers.query();
        queryWrapper.like("category_path", categoryId);
        queryWrapper.eq("delete_flag", false);
        return this.count(queryWrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addGoods(GoodsOperationDTO goodsOperationDTO) {
        Goods goods = new Goods(goodsOperationDTO);
        //????????????
        this.checkGoods(goods);
        //???goods????????????
        if (goodsOperationDTO.getGoodsGalleryList().size() > 0 ) {
            this.setGoodsGalleryParam(goodsOperationDTO.getGoodsGalleryList().get(0), goods);
        }
        //??????????????????
        if (goodsOperationDTO.getGoodsParamsDTOList() != null && !goodsOperationDTO.getGoodsParamsDTOList().isEmpty()) {
            //????????????????????????
            goods.setParams(JSONUtil.toJsonStr(goodsOperationDTO.getGoodsParamsDTOList()));
        }
        //????????????
        this.save(goods);
        //????????????sku??????
        this.goodsSkuService.add(goods, goodsOperationDTO);
        //????????????
        if (goodsOperationDTO.getGoodsGalleryList() != null && !goodsOperationDTO.getGoodsGalleryList().isEmpty()) {
            this.goodsGalleryService.add(goodsOperationDTO.getGoodsGalleryList(), goods.getId());
        }
        this.generateEs(goods);
    }



    @Override
    @Transactional(rollbackFor = Exception.class)
    public void editGoods(GoodsOperationDTO goodsOperationDTO, String goodsId) {
        Goods goods = new Goods(goodsOperationDTO);
        goods.setId(goodsId);
        //??????????????????
        this.checkGoods(goods);
        //???goods????????????
        this.setGoodsGalleryParam(goodsOperationDTO.getGoodsGalleryList().get(0), goods);
        //??????????????????
        if (goodsOperationDTO.getGoodsParamsDTOList() != null && !goodsOperationDTO.getGoodsParamsDTOList().isEmpty()) {
            goods.setParams(JSONUtil.toJsonStr(goodsOperationDTO.getGoodsParamsDTOList()));
        }
        //????????????
        this.updateById(goods);
        //????????????sku??????
        this.goodsSkuService.update(goods, goodsOperationDTO);
        //????????????
        if (goodsOperationDTO.getGoodsGalleryList() != null && !goodsOperationDTO.getGoodsGalleryList().isEmpty()) {
            this.goodsGalleryService.add(goodsOperationDTO.getGoodsGalleryList(), goods.getId());
        }
        if (GoodsAuthEnum.TOBEAUDITED.name().equals(goods.getAuthFlag())) {
            this.deleteEsGoods(Collections.singletonList(goodsId));
        }
        cache.remove(CachePrefix.GOODS.getPrefix() + goodsId);
        this.generateEs(goods);
    }


    @Override
    public GoodsVO getGoodsVO(String goodsId) {
        //??????????????????????????????????????????
        GoodsVO goodsVO = cache.get(CachePrefix.GOODS.getPrefix() + goodsId);
        if (goodsVO != null) {
            return goodsVO;
        }
        //??????????????????
        Goods goods = this.getById(goodsId);
        if (goods == null) {
            log.error("??????ID???" + goodsId + "??????????????????");
            throw new ServiceException(ResultCode.GOODS_NOT_EXIST);
        }
        goodsVO = new GoodsVO();
        //??????
        BeanUtils.copyProperties(goods, goodsVO);
        //??????id
        goodsVO.setId(goods.getId());
        //??????????????????
        List<String> images = new ArrayList<>();
        List<GoodsGallery> galleryList = goodsGalleryService.goodsGalleryList(goodsId);
        for (GoodsGallery goodsGallery : galleryList) {
            images.add(goodsGallery.getOriginal());
        }
        goodsVO.setGoodsGalleryList(images);
        //??????sku??????
        List<GoodsSkuVO> goodsListByGoodsId = goodsSkuService.getGoodsListByGoodsId(goodsId);
        if (goodsListByGoodsId != null && !goodsListByGoodsId.isEmpty()) {
            goodsVO.setSkuList(goodsListByGoodsId);
        }
        //????????????????????????
        List<String> categoryName = new ArrayList<>();
        String categoryPath = goods.getCategoryPath();
        String[] strArray = categoryPath.split(",");
        List<Category> categories = categoryService.listByIds(Arrays.asList(strArray));
        for (Category category : categories) {
            categoryName.add(category.getName());
        }
        goodsVO.setCategoryName(categoryName);

        //???????????????????????????
        if (CharSequenceUtil.isNotEmpty(goods.getParams())) {
            goodsVO.setGoodsParamsDTOList(JSONUtil.toList(goods.getParams(), GoodsParamsDTO.class));
        }

        List<Wholesale> wholesaleList = wholesaleService.findByGoodsId(goodsId);
        if (CollUtil.isNotEmpty(wholesaleList)) {
            goodsVO.setWholesaleList(wholesaleList);
        }

        cache.put(CachePrefix.GOODS.getPrefix() + goodsId, goodsVO);
        return goodsVO;
    }

    @Override
    public IPage<Goods> queryByParams(GoodsSearchParams goodsSearchParams) {
        return this.page(PageUtil.initPage(goodsSearchParams), goodsSearchParams.queryWrapper());
    }

    /**
     * ????????????
     *
     * @param goodsSearchParams ????????????
     * @return ????????????
     */
    @Override
    public List<Goods> queryListByParams(GoodsSearchParams goodsSearchParams) {
        return this.list(goodsSearchParams.queryWrapper());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean auditGoods(List<String> goodsIds, GoodsAuthEnum goodsAuthEnum) {
        List<String> goodsCacheKeys = new ArrayList<>();
        boolean result = false;
        for (String goodsId : goodsIds) {
            Goods goods = this.checkExist(goodsId);
            goods.setAuthFlag(goodsAuthEnum.name());
            result = this.updateById(goods);
            goodsSkuService.updateGoodsSkuStatus(goods);
            //?????????????????????
            goodsCacheKeys.add(CachePrefix.GOODS.getPrefix() + goodsId);
            //??????????????????
            String destination = rocketmqCustomProperties.getGoodsTopic() + ":" + GoodsTagsEnum.GOODS_AUDIT.name();
            //??????mq??????
            rocketMQTemplate.asyncSend(destination, JSONUtil.toJsonStr(goods), RocketmqSendCallbackBuilder.commonCallback());
        }
        cache.multiDel(goodsCacheKeys);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateGoodsMarketAble(List<String> goodsIds, GoodsStatusEnum goodsStatusEnum, String underReason) {
        boolean result;

        //?????????????????????????????????
        if (goodsIds == null || goodsIds.isEmpty()) {
            return true;
        }

        LambdaUpdateWrapper<Goods> updateWrapper = this.getUpdateWrapperByStoreAuthority();
        updateWrapper.set(Goods::getMarketEnable, goodsStatusEnum.name());
        updateWrapper.set(Goods::getUnderMessage, underReason);
        updateWrapper.in(Goods::getId, goodsIds);
        result = this.update(updateWrapper);

        //??????????????????
        LambdaQueryWrapper<Goods> queryWrapper = this.getQueryWrapperByStoreAuthority();
        queryWrapper.in(Goods::getId, goodsIds);
        List<Goods> goodsList = this.list(queryWrapper);
        this.updateGoodsStatus(goodsIds, goodsStatusEnum, goodsList);
        return result;
    }

    /**
     * ??????????????????????????????
     *
     * @param storeId         ??????ID
     * @param goodsStatusEnum ?????????????????????
     * @param underReason     ????????????
     * @return ????????????
     */
    @Override
    public Boolean updateGoodsMarketAbleByStoreId(String storeId, GoodsStatusEnum goodsStatusEnum, String underReason) {


        LambdaUpdateWrapper<Goods> updateWrapper = this.getUpdateWrapperByStoreAuthority();
        updateWrapper.set(Goods::getMarketEnable, goodsStatusEnum.name());
        updateWrapper.set(Goods::getUnderMessage, underReason);
        updateWrapper.eq(Goods::getStoreId, storeId);
        boolean result = this.update(updateWrapper);

        //??????????????????
        this.goodsSkuService.updateGoodsSkuStatusByStoreId(storeId, goodsStatusEnum.name(), null);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean managerUpdateGoodsMarketAble(List<String> goodsIds, GoodsStatusEnum goodsStatusEnum, String underReason) {
        boolean result;

        //?????????????????????????????????
        if (goodsIds == null || goodsIds.isEmpty()) {
            return true;
        }

        //?????????????????????
        this.checkManagerAuthority();

        LambdaUpdateWrapper<Goods> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.set(Goods::getMarketEnable, goodsStatusEnum.name());
        updateWrapper.set(Goods::getUnderMessage, underReason);
        updateWrapper.in(Goods::getId, goodsIds);
        result = this.update(updateWrapper);

        //??????????????????
        LambdaQueryWrapper<Goods> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(Goods::getId, goodsIds);
        List<Goods> goodsList = this.list(queryWrapper);
        this.updateGoodsStatus(goodsIds, goodsStatusEnum, goodsList);
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean deleteGoods(List<String> goodsIds) {

        LambdaUpdateWrapper<Goods> updateWrapper = this.getUpdateWrapperByStoreAuthority();
        updateWrapper.set(Goods::getMarketEnable, GoodsStatusEnum.DOWN.name());
        updateWrapper.set(Goods::getDeleteFlag, true);
        updateWrapper.in(Goods::getId, goodsIds);
        this.update(updateWrapper);

        //??????????????????
        LambdaQueryWrapper<Goods> queryWrapper = this.getQueryWrapperByStoreAuthority();
        queryWrapper.in(Goods::getId, goodsIds);
        List<Goods> goodsList = this.list(queryWrapper);
        List<String> goodsCacheKeys = new ArrayList<>();
        for (Goods goods : goodsList) {
            //??????SKU??????
            goodsSkuService.updateGoodsSkuStatus(goods);
            goodsCacheKeys.add(CachePrefix.GOODS.getPrefix() + goods.getId());
        }
        //????????????
        cache.multiDel(goodsCacheKeys);
        this.deleteEsGoods(goodsIds);
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean freight(List<String> goodsIds, String templateId) {

        AuthUser authUser = this.checkStoreAuthority();

        FreightTemplate freightTemplate = freightTemplateService.getById(templateId);
        if (freightTemplate == null) {
            throw new ServiceException(ResultCode.FREIGHT_TEMPLATE_NOT_EXIST);
        }
        if (authUser != null && !freightTemplate.getStoreId().equals(authUser.getStoreId())) {
            throw new ServiceException(ResultCode.USER_AUTHORITY_ERROR);
        }
        LambdaUpdateWrapper<Goods> lambdaUpdateWrapper = Wrappers.lambdaUpdate();
        lambdaUpdateWrapper.set(Goods::getTemplateId, templateId);
        lambdaUpdateWrapper.in(Goods::getId, goodsIds);
        return this.update(lambdaUpdateWrapper);
    }

    @Override
    public void updateStock(String goodsId, Integer quantity) {
        LambdaUpdateWrapper<Goods> lambdaUpdateWrapper = Wrappers.lambdaUpdate();
        lambdaUpdateWrapper.set(Goods::getQuantity, quantity);
        lambdaUpdateWrapper.eq(Goods::getId, goodsId);
        cache.remove(CachePrefix.GOODS.getPrefix() + goodsId);
        this.update(lambdaUpdateWrapper);
    }

    @Override
    public void updateGoodsCommentNum(String goodsId) {

        //??????????????????
        Goods goods = this.getById(goodsId);
        //????????????????????????
        goods.setCommentNum(goods.getCommentNum() + 1);

        //?????????????????????
        LambdaQueryWrapper<MemberEvaluation> goodEvaluationQueryWrapper = new LambdaQueryWrapper<>();
        goodEvaluationQueryWrapper.eq(MemberEvaluation::getId, goodsId);
        goodEvaluationQueryWrapper.eq(MemberEvaluation::getGrade, EvaluationGradeEnum.GOOD.name());
        //????????????
        long highPraiseNum = memberEvaluationService.count(goodEvaluationQueryWrapper);
        //?????????
        double grade = NumberUtil.mul(NumberUtil.div(highPraiseNum, goods.getCommentNum().doubleValue(), 2), 100);
        goods.setGrade(grade);
        this.updateById(goods);
    }

    /**
     * ???????????????????????????
     *
     * @param goodsId  ??????ID
     * @param buyCount ????????????
     */
    @Override
    public void updateGoodsBuyCount(String goodsId, int buyCount) {
        this.update(new LambdaUpdateWrapper<Goods>()
                .eq(Goods::getId, goodsId)
                .set(Goods::getBuyCount, buyCount));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStoreDetail(Store store) {
        UpdateWrapper updateWrapper = new UpdateWrapper<>()
                .eq("store_id", store.getId())
                .set("store_name", store.getStoreName())
                .set("self_operated", store.getSelfOperated());
        this.update(updateWrapper);
        goodsSkuService.update(updateWrapper);
    }

    @Override
    public long countStoreGoodsNum(String storeId) {
        return this.count(
                new LambdaQueryWrapper<Goods>()
                        .eq(Goods::getStoreId, storeId)
                        .eq(Goods::getDeleteFlag, Boolean.FALSE)
                        .eq(Goods::getAuthFlag, GoodsAuthEnum.PASS.name())
                        .eq(Goods::getMarketEnable, GoodsStatusEnum.UPPER.name()));
    }


    /**
     * ??????????????????
     *
     * @param goodsIds        ??????ID
     * @param goodsStatusEnum ????????????
     * @param goodsList       ????????????
     */
    private void updateGoodsStatus(List<String> goodsIds, GoodsStatusEnum goodsStatusEnum, List<Goods> goodsList) {
        List<String> goodsCacheKeys = new ArrayList<>();
        for (Goods goods : goodsList) {
            goodsCacheKeys.add(CachePrefix.GOODS.getPrefix() + goods.getId());
            goodsSkuService.updateGoodsSkuStatus(goods);
        }
        cache.multiDel(goodsCacheKeys);

        if (GoodsStatusEnum.DOWN.equals(goodsStatusEnum)) {
            this.deleteEsGoods(goodsIds);
        } else {
            this.updateEsGoods(goodsIds);
        }


        //????????????????????????
        if (goodsStatusEnum.equals(GoodsStatusEnum.DOWN)) {
            applicationEventPublisher.publishEvent(new TransactionCommitSendMQEvent("????????????",
                    rocketmqCustomProperties.getGoodsTopic(), GoodsTagsEnum.DOWN.name(), JSONUtil.toJsonStr(goodsIds)));
        }
    }

    /**
     * ????????????ES????????????
     *
     * @param goods ????????????
     */
    @Transactional
    public void generateEs(Goods goods) {
        // ???????????????????????????????????????????????????
        if (!GoodsStatusEnum.UPPER.name().equals(goods.getMarketEnable()) || !GoodsAuthEnum.PASS.name().equals(goods.getAuthFlag())) {
            return;
        }
        applicationEventPublisher.publishEvent(new TransactionCommitSendMQEvent("????????????", rocketmqCustomProperties.getGoodsTopic(), GoodsTagsEnum.GENERATOR_GOODS_INDEX.name(), goods.getId()));
    }

    /**
     * ????????????ES????????????
     *
     * @param goodsIds ??????id
     */
    @Transactional
    public void updateEsGoods(List<String> goodsIds) {
        applicationEventPublisher.publishEvent(new TransactionCommitSendMQEvent("????????????", rocketmqCustomProperties.getGoodsTopic(), GoodsTagsEnum.UPDATE_GOODS_INDEX.name(), goodsIds));
    }

    /**
     * ????????????es???????????????
     *
     * @param goodsIds ??????id
     */
    @Transactional
    public void deleteEsGoods(List<String> goodsIds) {
        applicationEventPublisher.publishEvent(new TransactionCommitSendMQEvent("????????????", rocketmqCustomProperties.getGoodsTopic(), GoodsTagsEnum.GOODS_DELETE.name(), JSONUtil.toJsonStr(goodsIds)));
    }

    /**
     * ????????????????????????
     *
     * @param origin ??????
     * @param goods  ??????
     */
    private void setGoodsGalleryParam(String origin, Goods goods) {
        GoodsGallery goodsGallery = goodsGalleryService.getGoodsGallery(origin);
        goods.setOriginal(goodsGallery.getOriginal());
        goods.setSmall(goodsGallery.getSmall());
        goods.setThumbnail(goodsGallery.getThumbnail());
    }

    /**
     * ??????????????????
     * ??????????????????????????????????????????????????????
     * ???????????????????????????????????????????????????
     * ????????????????????????
     * ??????????????????????????????
     * ?????????????????????????????????
     *
     * @param goods ??????
     */
    private void checkGoods(Goods goods) {
        //??????????????????
        switch (goods.getGoodsType()) {
            case "PHYSICAL_GOODS":
                if ("0".equals(goods.getTemplateId())) {
                    throw new ServiceException(ResultCode.PHYSICAL_GOODS_NEED_TEMP);
                }
                break;
            case "VIRTUAL_GOODS":
                if (!"0".equals(goods.getTemplateId())) {
                    goods.setTemplateId("0");
                }
                break;
            default:
                throw new ServiceException(ResultCode.GOODS_TYPE_ERROR);
        }
        //????????????????????????--?????????????????????
        if (goods.getId() != null) {
            this.checkExist(goods.getId());
        } else {
            //????????????
            goods.setCommentNum(0);
            //????????????
            goods.setBuyCount(0);
            //????????????
            goods.setQuantity(0);
            //????????????
            goods.setGrade(100.0);
        }

        //??????????????????????????????????????????
        Setting setting = settingService.get(SettingEnum.GOODS_SETTING.name());
        GoodsSetting goodsSetting = JSONUtil.toBean(setting.getSettingValue(), GoodsSetting.class);
        //??????????????????
        goods.setAuthFlag(Boolean.TRUE.equals(goodsSetting.getGoodsCheck()) ? GoodsAuthEnum.TOBEAUDITED.name() : GoodsAuthEnum.PASS.name());
        //?????????????????????????????????
        if (Objects.requireNonNull(UserContext.getCurrentUser()).getRole().equals(UserEnums.STORE)) {
            StoreVO storeDetail = this.storeService.getStoreDetail();
            if (storeDetail.getSelfOperated() != null) {
                goods.setSelfOperated(storeDetail.getSelfOperated());
            }
            goods.setStoreId(storeDetail.getId());
            goods.setStoreName(storeDetail.getStoreName());
            goods.setSelfOperated(storeDetail.getSelfOperated());
        } else {
            throw new ServiceException(ResultCode.STORE_NOT_LOGIN_ERROR);
        }
    }

    /**
     * ????????????????????????
     *
     * @param goodsId ??????id
     * @return ????????????
     */
    private Goods checkExist(String goodsId) {
        Goods goods = getById(goodsId);
        if (goods == null) {
            log.error("??????ID???" + goodsId + "??????????????????");
            throw new ServiceException(ResultCode.GOODS_NOT_EXIST);
        }
        return goods;
    }


    /**
     * ??????UpdateWrapper????????????????????????
     *
     * @return updateWrapper
     */
    private LambdaUpdateWrapper<Goods> getUpdateWrapperByStoreAuthority() {
        LambdaUpdateWrapper<Goods> updateWrapper = new LambdaUpdateWrapper<>();
        AuthUser authUser = this.checkStoreAuthority();
        if (authUser != null) {
            updateWrapper.eq(Goods::getStoreId, authUser.getStoreId());
        }
        return updateWrapper;
    }


    /**
     * ???????????????????????????
     *
     * @return ?????????????????????
     */
    private AuthUser checkStoreAuthority() {
        AuthUser currentUser = UserContext.getCurrentUser();
        //????????????????????????????????????????????????
        if (currentUser != null && (currentUser.getRole().equals(UserEnums.STORE) && currentUser.getStoreId() != null)) {
            return currentUser;
        }
        return null;
    }

    /**
     * ???????????????????????????
     *
     * @return ?????????????????????
     */
    private AuthUser checkManagerAuthority() {
        AuthUser currentUser = UserContext.getCurrentUser();
        //????????????????????????????????????????????????
        if (currentUser != null && (currentUser.getRole().equals(UserEnums.MANAGER))) {
            return currentUser;
        } else {
            throw new ServiceException(ResultCode.USER_AUTHORITY_ERROR);
        }
    }

    /**
     * ??????QueryWrapper????????????????????????
     *
     * @return queryWrapper
     */
    private LambdaQueryWrapper<Goods> getQueryWrapperByStoreAuthority() {
        LambdaQueryWrapper<Goods> queryWrapper = new LambdaQueryWrapper<>();
        AuthUser authUser = this.checkStoreAuthority();
        if (authUser != null) {
            queryWrapper.eq(Goods::getStoreId, authUser.getStoreId());
        }
        return queryWrapper;
    }

}
