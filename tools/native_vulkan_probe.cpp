// Standalone, opt-in Vulkan correctness/endurance probe. No SVPlayer code/assets.
#include <vulkan/vulkan.h>
#include <jni.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <cstring>
#include <sstream>
#include <stdexcept>
#include <thread>
#include <vector>
#include "vulkan_motion_spv.h"

namespace {
using Clock = std::chrono::steady_clock;
void check(VkResult r) { if(r!=VK_SUCCESS) throw std::runtime_error("Vulkan result="+std::to_string(r)); }
struct Params { uint32_t w,h; int32_t dx,dy; uint32_t phase; };
struct Buffer { VkBuffer buffer{}; VkDeviceMemory memory{}; uint32_t* data{}; };
struct Probe {
    VkInstance instance{}; VkDevice device{}; VkQueue queue{};
    VkPhysicalDevice physical{}; VkPhysicalDeviceProperties properties{};
    VkDescriptorSetLayout setLayout{}; VkPipelineLayout layout{};
    VkShaderModule shader{}; VkPipeline pipeline{}; VkDescriptorPool descriptors{};
    VkCommandPool commands{}; VkFence fence{}; std::array<Buffer,3> buffers{};
    bool pending=false;
    ~Probe() {
        // Diagnostic-only process must be stopped if a driver cannot finish. Never
        // free buffers that a timed-out submission could still be accessing.
        if(pending) return;
        if(device) {
            for(auto& b:buffers) {
                if(b.data) vkUnmapMemory(device,b.memory);
                if(b.buffer) vkDestroyBuffer(device,b.buffer,nullptr);
                if(b.memory) vkFreeMemory(device,b.memory,nullptr);
            }
            if(fence) vkDestroyFence(device,fence,nullptr);
            if(commands) vkDestroyCommandPool(device,commands,nullptr);
            if(descriptors) vkDestroyDescriptorPool(device,descriptors,nullptr);
            if(pipeline) vkDestroyPipeline(device,pipeline,nullptr);
            if(shader) vkDestroyShaderModule(device,shader,nullptr);
            if(layout) vkDestroyPipelineLayout(device,layout,nullptr);
            if(setLayout) vkDestroyDescriptorSetLayout(device,setLayout,nullptr);
            vkDestroyDevice(device,nullptr);
        }
        if(instance) vkDestroyInstance(instance,nullptr);
    }
    void init(size_t bytes) {
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName="SkyWing Vulkan diagnostic"; app.apiVersion=VK_API_VERSION_1_1;
        VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ci.pApplicationInfo=&app;
        check(vkCreateInstance(&ci,nullptr,&instance));
        uint32_t count=0; check(vkEnumeratePhysicalDevices(instance,&count,nullptr));
        std::vector<VkPhysicalDevice> devices(count); check(vkEnumeratePhysicalDevices(instance,&count,devices.data()));
        uint32_t family=0; bool found=false;
        for(auto d:devices) {
            uint32_t n=0; vkGetPhysicalDeviceQueueFamilyProperties(d,&n,nullptr);
            std::vector<VkQueueFamilyProperties> qs(n); vkGetPhysicalDeviceQueueFamilyProperties(d,&n,qs.data());
            for(uint32_t i=0;i<n;i++) if(qs[i].queueFlags&VK_QUEUE_COMPUTE_BIT) {
                physical=d; family=i; found=true; break;
            }
            if(found) break;
        }
        if(!found) throw std::runtime_error("No Vulkan compute queue");
        vkGetPhysicalDeviceProperties(physical,&properties);
        if(properties.limits.maxComputeWorkGroupInvocations<256 || properties.limits.maxComputeWorkGroupSize[0]<256)
            throw std::runtime_error("Diagnostic requires a 256-invocation compute workgroup");
        float priority=1;
        VkDeviceQueueCreateInfo q{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        q.queueFamilyIndex=family; q.queueCount=1; q.pQueuePriorities=&priority;
        VkDeviceCreateInfo dc{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; dc.queueCreateInfoCount=1; dc.pQueueCreateInfos=&q;
        check(vkCreateDevice(physical,&dc,nullptr,&device)); vkGetDeviceQueue(device,family,0,&queue);
        VkPhysicalDeviceMemoryProperties memory{}; vkGetPhysicalDeviceMemoryProperties(physical,&memory);
        for(auto& b:buffers) {
            VkBufferCreateInfo bc{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; bc.size=bytes;
            bc.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT; bc.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
            check(vkCreateBuffer(device,&bc,nullptr,&b.buffer));
            VkMemoryRequirements req{}; vkGetBufferMemoryRequirements(device,b.buffer,&req);
            uint32_t type=UINT32_MAX;
            for(uint32_t i=0;i<memory.memoryTypeCount;i++) if((req.memoryTypeBits&(1u<<i)) &&
                (memory.memoryTypes[i].propertyFlags&(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
                (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) { type=i; break; }
            if(type==UINT32_MAX) throw std::runtime_error("No coherent host-visible memory");
            VkMemoryAllocateInfo ma{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; ma.allocationSize=req.size; ma.memoryTypeIndex=type;
            check(vkAllocateMemory(device,&ma,nullptr,&b.memory)); check(vkBindBufferMemory(device,b.buffer,b.memory,0));
            void* mapped=nullptr; check(vkMapMemory(device,b.memory,0,bytes,0,&mapped)); b.data=static_cast<uint32_t*>(mapped);
        }
        std::array<VkDescriptorSetLayoutBinding,3> bindings{};
        for(uint32_t i=0;i<3;i++) bindings[i]={i,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,1,VK_SHADER_STAGE_COMPUTE_BIT,nullptr};
        VkDescriptorSetLayoutCreateInfo sl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        sl.bindingCount=3; sl.pBindings=bindings.data(); check(vkCreateDescriptorSetLayout(device,&sl,nullptr,&setLayout));
        VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(Params)};
        VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        pl.setLayoutCount=1; pl.pSetLayouts=&setLayout; pl.pushConstantRangeCount=1; pl.pPushConstantRanges=&range;
        check(vkCreatePipelineLayout(device,&pl,nullptr,&layout));
        VkShaderModuleCreateInfo sm{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        sm.codeSize=sizeof(vulkan_motion_spv); sm.pCode=vulkan_motion_spv;
        check(vkCreateShaderModule(device,&sm,nullptr,&shader));
        VkComputePipelineCreateInfo pc{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO}; pc.layout=layout;
        pc.stage={VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,nullptr,0,VK_SHADER_STAGE_COMPUTE_BIT,shader,"main",nullptr};
        check(vkCreateComputePipelines(device,VK_NULL_HANDLE,1,&pc,nullptr,&pipeline));
        VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,3};
        VkDescriptorPoolCreateInfo dp{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO}; dp.maxSets=1; dp.poolSizeCount=1; dp.pPoolSizes=&ps;
        check(vkCreateDescriptorPool(device,&dp,nullptr,&descriptors));
        VkDescriptorSetAllocateInfo da{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        da.descriptorPool=descriptors; da.descriptorSetCount=1; da.pSetLayouts=&setLayout;
        check(vkAllocateDescriptorSets(device,&da,&set));
        std::array<VkDescriptorBufferInfo,3> infos{}; std::array<VkWriteDescriptorSet,3> writes{};
        for(uint32_t i=0;i<3;i++) {
            infos[i]={buffers[i].buffer,0,bytes}; writes[i]={VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
            writes[i].dstSet=set; writes[i].dstBinding=i; writes[i].descriptorCount=1;
            writes[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; writes[i].pBufferInfo=&infos[i];
        }
        vkUpdateDescriptorSets(device,3,writes.data(),0,nullptr);
        VkCommandPoolCreateInfo cp{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        cp.queueFamilyIndex=family; cp.flags=VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        check(vkCreateCommandPool(device,&cp,nullptr,&commands));
        VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        ca.commandPool=commands; ca.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY; ca.commandBufferCount=1;
        check(vkAllocateCommandBuffers(device,&ca,&command));
        VkFenceCreateInfo fc{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO}; check(vkCreateFence(device,&fc,nullptr,&fence));
    }
    VkDescriptorSet set{}; VkCommandBuffer command{};
    void render(const Params& p) {
        check(vkResetFences(device,1,&fence)); check(vkResetCommandBuffer(command,0));
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; begin.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        check(vkBeginCommandBuffer(command,&begin));
        VkMemoryBarrier before{VK_STRUCTURE_TYPE_MEMORY_BARRIER}; before.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT; before.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_HOST_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&before,0,nullptr,0,nullptr);
        vkCmdBindPipeline(command,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);
        vkCmdBindDescriptorSets(command,VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,1,&set,0,nullptr);
        vkCmdPushConstants(command,layout,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(p),&p);
        vkCmdDispatch(command,(p.w*p.h*3/2+255)/256,1,1);
        VkMemoryBarrier after{VK_STRUCTURE_TYPE_MEMORY_BARRIER}; after.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT; after.dstAccessMask=VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,&after,0,nullptr,0,nullptr);
        check(vkEndCommandBuffer(command));
        VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO}; submit.commandBufferCount=1; submit.pCommandBuffers=&command;
        check(vkQueueSubmit(queue,1,&submit,fence)); pending=true;
        check(vkWaitForFences(device,1,&fence,VK_TRUE,3'000'000'000ULL)); pending=false;
    }
};
uint32_t reference(const Probe& probe,const Params& p,uint32_t i) {
    uint32_t area=p.w*p.h,w=p.w,h=p.h,offset=0; int dx=p.dx,dy=p.dy;
    if(i>=area) { offset=area+(i>=area+area/4?area/4:0); w/=2; h/=2; dx/=2; dy/=2; }
    int x=(i-offset)%w,y=(i-offset)/w;
    auto sample=[&](int n,int xx,int yy) { return probe.buffers[n].data[offset+std::clamp(yy,0,int(h)-1)*w+std::clamp(xx,0,int(w)-1)]; };
    uint32_t a=sample(0,x-dx*int(p.phase)/256,y-dy*int(p.phase)/256);
    uint32_t b=sample(1,x+dx*int(256-p.phase)/256,y+dy*int(256-p.phase)/256);
    return (a*(256-p.phase)+b*p.phase+128)/256;
}
std::string run(uint32_t width,uint32_t height,int durationMs) {
    Probe probe; const uint32_t samples=width*height*3/2; probe.init(size_t(samples)*4);
    auto start=Clock::now(); auto until=start+std::chrono::milliseconds(durationMs);
    std::vector<double> latency; uint64_t checked=0; uint32_t frame=0;
    do {
        auto workStart=Clock::now();
        // Changing stripe/checker data in every plane defeats pass-through shortcuts.
        for(uint32_t i=0;i<samples;i++) {
            probe.buffers[0].data[i]=((i*13u)^(i/width*31u)^(frame*7u))&255;
            probe.buffers[1].data[i]=((i*29u)^(i/width*17u)^(frame*11u))&255;
            probe.buffers[2].data[i]=0xdeadbeef;
        }
        constexpr uint32_t phases[]{0,64,128,192,256,113};
        Params p{width,height,int(frame%65)-32,int(frame%33)-16,frame<6?phases[frame]:(frame*37)%257};
        probe.render(p);
        bool all=frame<6 || frame%120==0;
        uint32_t stride=all?1:7919;
        for(uint32_t i=0;i<samples;i+=stride) {
            if(probe.buffers[2].data[i]!=reference(probe,p,i)) throw std::runtime_error("Pixel mismatch at frame="+std::to_string(frame)+" sample="+std::to_string(i));
            checked++;
        }
        double elapsed=std::chrono::duration<double,std::milli>(Clock::now()-workStart).count();
        if(!all) latency.push_back(elapsed); // Full CPU validation is deliberately excluded from timing.
        frame++;
        std::this_thread::sleep_until(start+std::chrono::microseconds(int64_t(frame)*1'000'000/60));
    } while(frame<120 || Clock::now()<until);
    std::sort(latency.begin(),latency.end());
    auto percentile=[&](double f) { return latency[std::min(latency.size()-1,size_t(f*latency.size()))]; };
    std::ostringstream result;
    result<<"device="<<probe.properties.deviceName<<" size="<<width<<"x"<<height<<" frames="<<frame
        <<" checked="<<checked<<" mismatches=0 p50_ms="<<percentile(.50)<<" p95_ms="<<percentile(.95)
        <<" elapsed_s="<<std::chrono::duration<double>(Clock::now()-start).count();
    return result.str();
}
}
extern "C" JNIEXPORT jstring JNICALL Java_com_deepak_videolibrary_player_VulkanMotionProbe_run(
    JNIEnv* env,jobject,jint width,jint height,jint durationMs) {
    try {
        if(width<4 || height<4 || width>1920 || height>1080 || ((width|height)&1) || durationMs<2000 || durationMs>60000)
            throw std::runtime_error("Unsupported probe dimensions/duration");
        return env->NewStringUTF(run(width,height,durationMs).c_str());
    } catch(const std::exception& e) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what()); return nullptr;
    }
}
