/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.tencent.devops.store.service.image.impl

import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.pipeline.type.docker.ImageType
import com.tencent.devops.common.redis.RedisLock
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.project.api.service.ServiceProjectResource
import com.tencent.devops.project.pojo.ProjectCreateInfo
import com.tencent.devops.store.dao.image.ImageDao
import com.tencent.devops.store.pojo.common.PASS
import com.tencent.devops.store.pojo.common.enums.ReleaseTypeEnum
import com.tencent.devops.store.pojo.image.enums.ImageAgentTypeEnum
import com.tencent.devops.store.pojo.image.enums.ImageRDTypeEnum
import com.tencent.devops.store.pojo.image.request.ApproveImageReq
import com.tencent.devops.store.pojo.image.request.MarketImageRelRequest
import com.tencent.devops.store.pojo.image.request.MarketImageUpdateRequest
import com.tencent.devops.store.service.image.ImageReleaseService
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Service
import javax.annotation.PostConstruct
import javax.ws.rs.core.Response

@Service
@DependsOn("springContextUtil")
class SampleImageInitService @Autowired constructor(
    private val client: Client,
    private val dslContext: DSLContext,
    private val redisOperation: RedisOperation,
    private val imageDao: ImageDao,
    private val imageReleaseService: ImageReleaseService
) {

    private val logger = LoggerFactory.getLogger(SampleImageInitService::class.java)

    @PostConstruct
    fun imageInit() {
        val projectCode = "demo"
        val userId = "admin"
        logger.info("begin init image")
        val redisLock =
            RedisLock(redisOperation = redisOperation, lockKey = "IMAGE_INIT_LOCK", expiredTimeInSeconds = 60)
        if (redisLock.tryLock()) {
            try {
                // 创建demo项目
                val demoProjectResult = client.get(ServiceProjectResource::class).get(projectCode)
                if (demoProjectResult.isNotOk()) {
                    throw ErrorCodeException(
                        statusCode = Response.Status.INTERNAL_SERVER_ERROR.statusCode,
                        errorCode = demoProjectResult.code.toString(),
                        defaultMessage = demoProjectResult.message
                    )
                }
                if (demoProjectResult.isOk() && demoProjectResult.data == null) {
                    val createDemoProjectResult = client.get(ServiceProjectResource::class).create(
                        userId = userId,
                        projectCreateInfo = ProjectCreateInfo(
                            projectName = "Demo",
                            englishName = projectCode,
                            description = "demo project"
                        )
                    )
                    if (createDemoProjectResult.isNotOk() || createDemoProjectResult.data != true) {
                        throw ErrorCodeException(
                            statusCode = Response.Status.INTERNAL_SERVER_ERROR.statusCode,
                            errorCode = createDemoProjectResult.code.toString(),
                            defaultMessage = createDemoProjectResult.message
                        )
                    }
                }
                val imageCode = "tlinux_ci"
                // 新增镜像
                val imageCount = imageDao.countByCode(dslContext, imageCode)
                if (imageCount != 0) {
                    return
                }
                val addImageResult = imageReleaseService.addMarketImage(
                    accessToken = "",
                    userId = userId,
                    imageCode = imageCode,
                    marketImageRelRequest = MarketImageRelRequest(
                        projectCode = projectCode,
                        imageName = imageCode,
                        imageSourceType = ImageType.THIRD,
                        ticketId = null
                    ),
                    needAuth = false
                )
                if (addImageResult.isNotOk() || addImageResult.data.isNullOrBlank()) {
                    throw ErrorCodeException(
                        statusCode = Response.Status.INTERNAL_SERVER_ERROR.statusCode,
                        errorCode = addImageResult.status.toString(),
                        defaultMessage = addImageResult.message
                    )
                }
                // 更新镜像
                val updateImageResult = imageReleaseService.updateMarketImage(
                    userId = userId,
                    marketImageUpdateRequest = MarketImageUpdateRequest(
                        imageCode = imageCode,
                        imageName = imageCode,
                        classifyCode = "BASE",
                        labelIdList = null,
                        category = "PIPELINE_JOB",
                        agentTypeScope = ImageAgentTypeEnum.getAllAgentTypes(),
                        summary = "CI basic image based on tlinux2.2",
                        description = "Docker public build machine build machine base image",
                        logoUrl = "/ms/artifactory/api/user/artifactories/file/download?filePath=%2Ffile%2Fpng%2FgithubTrigger.png",
                        iconData = null,
                        ticketId = null,
                        imageSourceType = ImageType.THIRD,
                        imageRepoUrl = "",
                        imageRepoName = "bkci/ci",
                        imageTag = "latest",
                        dockerFileType = "INPUT",
                        dockerFileContent = "FROM bkci/ci:latest\n RUN apt install -y git python-pip python3-pip\n",
                        version = "1.0.0",
                        releaseType = ReleaseTypeEnum.NEW,
                        versionContent = "tlinux_ci",
                        publisher = userId
                    ),
                    checkLatest = false,
                    sendCheckResultNotify = false,
                    runCheckPipeline = false
                )
                if (updateImageResult.isNotOk() || updateImageResult.data.isNullOrBlank()) {
                    throw ErrorCodeException(
                        statusCode = Response.Status.INTERNAL_SERVER_ERROR.statusCode,
                        errorCode = updateImageResult.status.toString(),
                        defaultMessage = updateImageResult.message
                    )
                }
                val imageId = updateImageResult.data!!
                // 自动让镜像测试通过
                imageReleaseService.approveImage(
                    userId = userId,
                    imageId = imageId,
                    approveImageReq = ApproveImageReq(
                        imageCode = imageCode,
                        publicFlag = true,
                        recommendFlag = true,
                        certificationFlag = false,
                        rdType = ImageRDTypeEnum.THIRD_PARTY,
                        weight = 1,
                        result = PASS,
                        message = "ok"
                    )
                )
            } finally {
                redisLock.unlock()
            }
        }
    }
}
