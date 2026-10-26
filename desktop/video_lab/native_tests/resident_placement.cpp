#include <cstdio>
#include <unordered_map>
#include <vector>
#include "ggml.h"
#include "ggml-cpu.h"
#include "core/layer_split_partition.h"

static size_t simulated_free = 4ull << 30;
extern "C" void __wrap_ggml_backend_dev_memory(ggml_backend_dev_t, size_t* free_bytes, size_t* total_bytes) {
    *free_bytes = simulated_free;
    *total_bytes = 4ull << 30;
}

int main() {
    ggml_init_params init = {1024 * 1024, nullptr, true};
    ggml_context* context = ggml_init(init);
    ggml_tensor* input = ggml_new_tensor_2d(context, GGML_TYPE_F32, 4, 1);
    ggml_tensor* first_weight = ggml_new_tensor_2d(context, GGML_TYPE_F32, 4, 4);
    ggml_tensor* second_weight = ggml_new_tensor_2d(context, GGML_TYPE_F32, 4, 4);
    ggml_set_input(input);
    ggml_tensor* first = ggml_mul_mat(context, first_weight, input);
    ggml_tensor* second = ggml_mul_mat(context, second_weight, first);
    ggml_cgraph* graph = ggml_new_graph(context);
    ggml_build_forward_expand(graph, second);
    sd::ggml_graph_cut::Plan plan;
    plan.available = plan.has_cuts = true;
    plan.n_nodes = ggml_graph_n_nodes(graph);
    plan.n_leafs = sd::ggml_graph_cut::leaf_count(graph);
    for (int index = 0; index < 2; ++index) {
        sd::ggml_graph_cut::Segment segment;
        segment.internal_node_indices = {index};
        segment.output_node_indices = {index};
        sd::ggml_graph_cut::Segment::InputRef ref;
        ref.type = sd::ggml_graph_cut::Segment::INPUT_PARAM;
        ggml_tensor* weight = index == 0 ? first_weight : second_weight;
        for (int leaf = 0; leaf < plan.n_leafs; ++leaf) {
            if (sd::ggml_graph_cut::leaf_tensor(graph, leaf) == weight) ref.leaf_index = leaf;
        }
        if (ref.leaf_index < 0) return 1;
        segment.input_refs.push_back(ref);
        plan.segments.push_back(segment);
    }
    std::vector<ggml_backend_t> backends = {ggml_backend_cpu_init(), ggml_backend_cpu_init()};
    std::unordered_map<const ggml_tensor*, ggml_backend_t> resident;
    sd::GraphCutLayerSplitAssignment assignment;
    auto partition = [&](auto& map) {
        return sd::partition_graph_cut_layer_split("regression", graph, plan, backends, {96, 96}, 0, map,
                                                   [](ggml_tensor* tensor) { return tensor; }, &assignment);
    };
    if (!partition(resident) || resident[first_weight] != backends[0] || resident[second_weight] != backends[1]) return 1;
    simulated_free = 1024;
    if (!partition(resident)) {
        std::fprintf(stderr, "Repeated placement failed despite unchanged resident weights\n");
        return 2;
    }
    if (resident[first_weight] != backends[0] || resident[second_weight] != backends[1]) return 3;
    std::unordered_map<const ggml_tensor*, ggml_backend_t> fresh;
    if (partition(fresh)) return 4;
    for (ggml_backend_t backend : backends) ggml_backend_free(backend);
    ggml_free(context);
    std::puts("Resident placement reused; fresh placement still rejects insufficient memory");
    return 0;
}
