package com.jiuyu.service.impl;

import java.util.*;
import java.util.stream.Collectors;

import com.jiuyu.raw.MarriageEvidence;
import org.fisco.bcos.sdk.abi.datatypes.generated.Bytes32;
import org.fisco.bcos.sdk.client.Client;
import org.fisco.bcos.sdk.crypto.CryptoSuite;
import org.fisco.bcos.sdk.crypto.keypair.CryptoKeyPair;
import org.fisco.bcos.sdk.model.CryptoType;
import org.fisco.bcos.sdk.model.TransactionReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jiuyu.common.GlobalConstant;
import com.jiuyu.common.Query;
import com.jiuyu.common.R;
import com.jiuyu.dao.AdminUserDao;
import com.jiuyu.dao.ContractTemplateDao;
import com.jiuyu.dao.MarriageEvidenceDao;
import com.jiuyu.dao.MarriageInfoDao;
import com.jiuyu.dao.UserInfoDao;
import com.jiuyu.entity.AdminUserEntity;
import com.jiuyu.entity.ContractTemplateEntity;
import com.jiuyu.entity.MarriageEvidenceEntity;
import com.jiuyu.entity.MarriageInfoEntity;
import com.jiuyu.entity.UserInfoEntity;
import com.jiuyu.enums.CertStatusEnum;
import com.jiuyu.enums.MarriageStatusEnum;
import com.jiuyu.service.MarriageEvidenceService;
import com.jiuyu.utils.CommonUtils;
import com.jiuyu.utils.DateUtils;
import com.jiuyu.utils.PageUtils;
import com.jiuyu.vo.ReqCreateMarriageEvidenceVo;
import com.jiuyu.vo.ReqSignVo;
import com.jiuyu.vo.ResCreateMarriageEvidenceVo;
import com.jiuyu.vo.ResMarriageDeatailVo;
import com.jiuyu.vo.ResSignVo;


@Service("marriageEvidenceService")
public class MarriageEvidenceServiceImpl extends ServiceImpl<MarriageEvidenceDao, MarriageEvidenceEntity> implements MarriageEvidenceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MarriageEvidenceServiceImpl.class);

    @Autowired
    private MarriageEvidenceDao marriageEvidenceDao;

    @Autowired
    private AdminUserDao adminUserDao;

    @Autowired
    private UserInfoDao userInfoDao;

    @Autowired
    private ContractTemplateDao contractTemplateDao;

    @Autowired
    private MarriageInfoDao marriageInfoDao;

    @Autowired
    private Client client;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<MarriageEvidenceEntity> page = this.page(
                new Query<MarriageEvidenceEntity>().getPage(params),
                new QueryWrapper<MarriageEvidenceEntity>());

        return new PageUtils(page);
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    @Override
    public ResCreateMarriageEvidenceVo createMarriageEvidence(ReqCreateMarriageEvidenceVo req) {
        MarriageEvidenceEntity evidence = new MarriageEvidenceEntity();

        Date date = new Date();
        String now = DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, date);
        Long maleUserId = req.getMaleUserId();
        UserInfoEntity dbMale = userInfoDao.selectById(maleUserId);
        Long femaleUserId = req.getFemaleUserId();
        UserInfoEntity dbFemale = userInfoDao.selectById(femaleUserId);
        Long witnessUserId = req.getWitnessUserId();
        UserInfoEntity dbWitness = userInfoDao.selectById(witnessUserId);

        // 1.??????????????????????????????????????????
        AdminUserEntity dbAdmin = adminUserDao.selectById(req.getAdminUserId());
        ContractTemplateEntity template = contractTemplateDao.queryByTemplate(GlobalConstant.CONTRACT_TEMPLATE);
        MarriageEvidence marriageEvidence = new MarriageEvidence(template.getContractAddress(), client, client.getCryptoSuite().getCryptoKeyPair());

        //??????UUID??????????????????
        String certificateNumber = UUID.randomUUID().toString().replaceAll("-", "");
        String witness = dbWitness.getUsername();

        //??????tbl_user_info????????????????????????
        dbMale.setCertificateNumber(certificateNumber);
        dbMale.setMarriageStatus(MarriageStatusEnum.NO_MARRIAGE.getCode());
        userInfoDao.updateById(dbMale);
        dbFemale.setCertificateNumber(certificateNumber);
        dbFemale.setMarriageStatus(MarriageStatusEnum.NO_MARRIAGE.getCode());
        userInfoDao.updateById(dbFemale);
        dbWitness.setCertificateNumber(certificateNumber);
        dbWitness.setMarriageStatus(MarriageStatusEnum.NO_MARRIAGE.getCode());
        userInfoDao.updateById(dbWitness);

        Bytes32 bytes32 = CommonUtils.utf8StringToBytes32(witness);
        byte[] witnessName = bytes32.getValue();

        //??????deployEvi??????
        String witnessAddress = dbWitness.getPublicAddress();
        String maleAddress = dbMale.getPublicAddress();
        String maleSummary = JSONUtil.toJsonStr(dbMale);
        String femaleAddress = dbFemale.getPublicAddress();
        String femaleSummary = JSONUtil.toJsonStr(dbFemale);
        TransactionReceipt deployEvi = marriageEvidence.deployEvi(certificateNumber, maleAddress, maleSummary, femaleAddress, femaleSummary, witnessAddress, witnessName);
        LOGGER.info("??????deployEvi??????,????????????:>>{}", deployEvi);

        // 2.??????newEvi ??????
        StringBuffer sb = new StringBuffer();
        sb.append("?????????").append(dbMale.getUsername()).append("????????????").append("???")
                .append(dbFemale.getUsername()).append("????????????").append(now)
                .append("????????????????????????!").append("????????????").append(dbWitness.getUsername());
        TransactionReceipt newEvi = marriageEvidence.newEvi(certificateNumber, sb.toString());
        LOGGER.info("??????newEvi??????,????????????:>>{}", newEvi);
        String transactionHash = newEvi.getTransactionHash();

        // 3.??????tbl_marriage_info ???
        MarriageInfoEntity marriageInfo = new MarriageInfoEntity();
        marriageInfo.setCertificateNumber(certificateNumber);
        marriageInfo.setMaleUsername(dbMale.getUsername());
        marriageInfo.setMaleUserId(dbMale.getId());
        marriageInfo.setMaleSignUserId(dbMale.getSignUserId());
        marriageInfo.setMalePublicAddress(dbMale.getPublicAddress());
        marriageInfo.setFemaleUsername(dbFemale.getUsername());
        marriageInfo.setFemaleUserId(femaleUserId);
        marriageInfo.setFemaleSignUserId(dbFemale.getSignUserId());
        marriageInfo.setFemalePublicAddress(dbFemale.getPublicAddress());
        marriageInfo.setWitnessUsername(dbWitness.getUsername());
        marriageInfo.setWitnessUserId(witnessUserId);
        marriageInfo.setWitnessSignUserId(dbWitness.getSignUserId());
        marriageInfo.setWitnessPublicAddress(dbWitness.getPublicAddress());
        marriageInfo.setCertStatus(CertStatusEnum.DRAFT.getMsg());
        marriageInfo.setInsertTime(date);
        marriageInfo.setUpdateTime(date);
        marriageInfo.setMarriageDesc(sb.toString());
        marriageInfoDao.insert(marriageInfo);


        // 4.??????tbl_marriage_evidence???
        List<String> needSigners = new ArrayList<String>();
        needSigners.add(dbAdmin.getPublicAddress());
        needSigners.add(dbMale.getPublicAddress());
        needSigners.add(dbFemale.getPublicAddress());
        needSigners.add(dbWitness.getPublicAddress());
        List<String> hasSigners = new ArrayList<String>();
        hasSigners.add(dbAdmin.getPublicAddress());
        List<String> txIds = new ArrayList<String>();
        txIds.add(transactionHash);

        evidence.setEvidenceKey(newEvi.getLogs().get(0).getAddress());
        evidence.setEvidenceValue(sb.toString());
        evidence.setNeedSigners(JSONUtil.toJsonStr(needSigners));
        evidence.setHasSigners(JSONUtil.toJsonStr(hasSigners));
        evidence.setTxId(JSONUtil.toJsonStr(txIds));
        evidence.setInsertTime(date);
        evidence.setUpdateTime(date);
        evidence.setCertificateNumber(certificateNumber);
        marriageEvidenceDao.insert(evidence);

        ResCreateMarriageEvidenceVo res = new ResCreateMarriageEvidenceVo();
        res.setTxHash(transactionHash);
        return res;
    }

    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    @Override
    public R sign(ReqSignVo req) {
        Date now = new Date();
        Long userId = req.getUserId();
        UserInfoEntity dbUser = userInfoDao.selectById(userId);
        String privateKey = dbUser.getPrivateKey();
        String publicAddress = dbUser.getPublicAddress();
        MarriageEvidenceEntity dbEvi = marriageEvidenceDao.queryByUserPublicAddress(dbUser.getPublicAddress());

        JSONArray needSignerArray = JSONUtil.parseArray(dbEvi.getNeedSigners());
        List<String> needSignerList = JSONUtil.toList(needSignerArray, String.class);

        JSONArray hasSignerArray = JSONUtil.parseArray(dbEvi.getHasSigners());
        List<String> hasSignerList = JSONUtil.toList(hasSignerArray, String.class);
        if (needSignerList.contains(publicAddress) && hasSignerArray.contains(publicAddress)) {
            return R.error("????????????????????????????????????");
        }

        // 1.??????????????????
        ContractTemplateEntity template = contractTemplateDao.queryByTemplate(GlobalConstant.CONTRACT_TEMPLATE);
        //??????base64???16??????
        String hexPrivateKey = new String(Base64.getDecoder().decode(privateKey));
        CryptoKeyPair loadAccountFromHexPrivateKey = loadAccountFromHexPrivateKey(CryptoType.ECDSA_TYPE, hexPrivateKey);
        //??????sign??????
        MarriageEvidence marriageEvidence = new MarriageEvidence(template.getContractAddress(), client, loadAccountFromHexPrivateKey);
        TransactionReceipt sign = marriageEvidence.sign(req.getCertificateNumber());
        LOGGER.info("??????sign??????,????????????:>>{}", sign);

        String transactionHash = sign.getTransactionHash();

        // 2.??????tbl_user_info???
        dbUser.setUpdateTime(now);
        dbUser.setMarriageStatus(MarriageStatusEnum.HAS_MARRIAHE.getCode());
        userInfoDao.updateById(dbUser);

        // 3.??????tbl_marriage_evidence???
        JSONArray txIdArray = JSONUtil.parseArray(dbEvi.getTxId());
        List<String> txIdList = JSONUtil.toList(txIdArray, String.class);
        txIdList.add(transactionHash);

        hasSignerList.add(dbUser.getPublicAddress());
        dbEvi.setHasSigners(JSONUtil.toJsonStr(hasSignerList));
        dbEvi.setTxId(JSONUtil.toJsonStr(txIdList));
        dbEvi.setUpdateTime(now);
        marriageEvidenceDao.updateById(dbEvi);

        // 4.??????tbl_marriage_info???,?????????????????????????????????????????????????????????????????????????????????
        boolean flag = needSignerList.stream().sorted().collect(Collectors.joining()).equals(hasSignerList.stream().sorted().collect(Collectors.joining()));
        if (flag) {
            MarriageInfoEntity dbMarriageInfo = marriageInfoDao.selectByUserId(userId);
            dbMarriageInfo.setUpdateTime(now);
            dbMarriageInfo.setCertStatus(CertStatusEnum.CONFIRMED.getMsg());
            marriageInfoDao.updateById(dbMarriageInfo);
        }

        ResSignVo res = new ResSignVo();
        res.setTxHash(transactionHash);
        return R.ok(res);
    }

    @Transactional(propagation = Propagation.SUPPORTS, rollbackFor = Exception.class)
    @Override
    public ResMarriageDeatailVo queryMarriageDetail(String certificateNumber) {
        MarriageInfoEntity dbMarriage = marriageInfoDao.selectById(certificateNumber);

        ResMarriageDeatailVo res = new ResMarriageDeatailVo();

        BeanUtils.copyProperties(dbMarriage, res);
        UserInfoEntity dbMale = userInfoDao.selectById(dbMarriage.getMaleUserId());
        if (MarriageStatusEnum.NO_MARRIAGE.getCode().equals(dbMale.getMarriageStatus())) {
            res.setMaleSignStatus("?????????");
        } else {
            res.setMaleSignStatus("?????????");
        }

        UserInfoEntity dbFeMale = userInfoDao.selectById(dbMarriage.getFemaleUserId());
        if (MarriageStatusEnum.NO_MARRIAGE.getCode().equals(dbFeMale.getMarriageStatus())) {
            res.setFemaleSignStatus("?????????");
        } else {
            res.setFemaleSignStatus("?????????");
        }

        //???????????????
        UserInfoEntity dbWitness = userInfoDao.selectById(dbMarriage.getWitnessUserId());
        if (MarriageStatusEnum.NO_MARRIAGE.getCode().equals(dbWitness.getMarriageStatus())) {
            res.setWitnessSignStatus("?????????");
        } else {
            res.setWitnessSignStatus("?????????");
        }

        res.setCertificateNumber(certificateNumber);
        return res;
    }

    private CryptoKeyPair loadAccountFromHexPrivateKey(int cryptoType, String hexPrivateKey) {
        // ??????cryptoType??????cryptoSuite???cryptoType???????????????
        // 1. CryptoType.ECDSA_TYPE: ??????????????????????????????CryptoSuite
        // 2. CryptoType.SM_TYPE:    ???????????????????????????CryptoSuite
        CryptoSuite cryptoSuite = new CryptoSuite(cryptoType);
        // ??????????????????????????????hexPrivateKey??????????????????
        return cryptoSuite.getKeyPairFactory().createKeyPair(hexPrivateKey);
    }

}