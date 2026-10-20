<template>
  <div class="app-container overview">
    <div class="hero">
      <h1>餐饮门店经营管理系统</h1>
      <p class="sub">
        以「门店经营」为主线的多模块后端：权限体系、订单状态机、缓存与并发控制，
        并通过 Spring AI 接入大模型，落地自然语言查询、工具调用与知识库问答。
      </p>
    </div>

    <el-row :gutter="16">
      <el-col v-for="item in abilities" :key="item.title" :sm="24" :md="12" :lg="6">
        <el-card shadow="hover" class="ability">
          <div class="ability-title">
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.title }}</span>
          </div>
          <div class="ability-desc">{{ item.desc }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="stack">
      <template #header><span class="stack-title">技术栈</span></template>
      <el-tag v-for="t in stack" :key="t" class="stack-tag" effect="plain">{{ t }}</el-tag>
    </el-card>

    <el-alert type="info" :closable="false" show-icon class="tip">
      <template #title>
        从左侧「智能问答 → 知识库问答」进入，可上传文档并体验检索、生成与引用溯源。
      </template>
    </el-alert>
  </div>
</template>

<script setup name="Index">
const abilities = [
  {
    icon: 'Search',
    title: '自然语言查询',
    desc: '中文问题直接生成 SQL。四层校验加列白名单，越权访问与笛卡尔积在语法树层被拦下，而不是靠提示词自律。'
  },
  {
    icon: 'MagicStick',
    title: '工具调用 Agent',
    desc: '模型按自然语言调用后端业务方法，把「帮我下单一份宫保鸡丁」翻译成一串受控的服务调用。'
  },
  {
    icon: 'Document',
    title: '知识库问答',
    desc: '父子块检索：小块负责被搜准，大块负责被读懂。上下文压到原文的 12%，答案附带可溯源的引用。'
  },
  {
    icon: 'Lock',
    title: '缓存与并发',
    desc: '布隆过滤器防穿透，逻辑过期加分布式锁重建防击穿，扣库存用条件 UPDATE 而非版本号 CAS。'
  }
]

const stack = [
  'Java 17',
  'Spring Boot',
  'Spring AI',
  'MyBatis',
  'MySQL',
  'Redis',
  'Redisson',
  'Vue 3',
  'Element Plus'
]
</script>

<style scoped>
.overview {
  padding-top: 8px;
}

.hero {
  margin-bottom: 20px;
}

.hero h1 {
  margin: 0 0 10px;
  font-size: 24px;
  font-weight: 600;
  color: #303133;
}

.hero .sub {
  margin: 0;
  font-size: 14px;
  line-height: 1.8;
  color: #606266;
}

.ability {
  margin-bottom: 16px;
  min-height: 150px;
}

.ability-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 10px;
}

.ability-desc {
  font-size: 13px;
  line-height: 1.8;
  color: #606266;
}

.stack {
  margin-bottom: 16px;
}

.stack-title {
  font-weight: 600;
}

.stack-tag {
  margin: 0 8px 8px 0;
}

.tip {
  margin-bottom: 8px;
}
</style>
