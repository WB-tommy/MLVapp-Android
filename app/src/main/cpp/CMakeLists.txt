cmake_minimum_required(VERSION 3.10)

project(mlvcore C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(MLV_SRC_DIR ${CMAKE_SOURCE_DIR}/src)

add_library(processing STATIC
        ${MLV_SRC_DIR}/processing/blur_threaded.c
        ${MLV_SRC_DIR}/processing/cube_lut.c
        ${MLV_SRC_DIR}/processing/image_profiles.c
        ${MLV_SRC_DIR}/processing/processing.c
        ${MLV_SRC_DIR}/processing/raw_processing.c
        ${MLV_SRC_DIR}/processing/white_balance.c
        ${MLV_SRC_DIR}/processing/cafilter/ColorAberrationCorrection.c
        ${MLV_SRC_DIR}/processing/denoiser/denoiser_2d_median.c
        ${MLV_SRC_DIR}/processing/filter/filter.c
        ${MLV_SRC_DIR}/processing/filter/genann/genann.c
        ${MLV_SRC_DIR}/processing/interpolation/cosine_interpolation.c
        ${MLV_SRC_DIR}/processing/interpolation/spline_helper.cpp
        ${MLV_SRC_DIR}/processing/rbfilter/RBFilterPlain.cpp
        ${MLV_SRC_DIR}/processing/rbfilter/rbf_wrapper.cpp
        ${MLV_SRC_DIR}/processing/sobel/sobel.c
        ${MLV_SRC_DIR}/processing/tinyexpr/tinyexpr.c
)

add_library(ca STATIC
        ${MLV_SRC_DIR}/ca_correct/CA_correct_RT.c
)

add_library(debayer STATIC
        ${MLV_SRC_DIR}/debayer/ahdOld.c
        ${MLV_SRC_DIR}/debayer/amaze_demosaic.c
        ${MLV_SRC_DIR}/debayer/basic.c
        ${MLV_SRC_DIR}/debayer/conv.c
        ${MLV_SRC_DIR}/debayer/debayer.c
        ${MLV_SRC_DIR}/debayer/sleefsseavx.c
        ${MLV_SRC_DIR}/debayer/wb_conversion.c
)

add_library(dng STATIC
        ${MLV_SRC_DIR}/dng/dng.c
)

add_library(matrix STATIC
        ${MLV_SRC_DIR}/matrix/matrix.c
)
add_library(mlv STATIC
        ${MLV_SRC_DIR}/mlv/audio_mlv.c
        ${MLV_SRC_DIR}/mlv/frame_caching.c
        ${MLV_SRC_DIR}/mlv/video_mlv.c
        ${MLV_SRC_DIR}/mlv/camid/camera_id.c
        ${MLV_SRC_DIR}/mlv/liblj92/lj92.c
        ${MLV_SRC_DIR}/mlv/llrawproc/darkframe.c
        ${MLV_SRC_DIR}/mlv/llrawproc/dualiso.c
        ${MLV_SRC_DIR}/mlv/llrawproc/hist.c
        ${MLV_SRC_DIR}/mlv/llrawproc/llrawproc.c
        ${MLV_SRC_DIR}/mlv/llrawproc/patternnoise.c
        ${MLV_SRC_DIR}/mlv/llrawproc/pixelproc.c
        ${MLV_SRC_DIR}/mlv/llrawproc/stripes.c
        ${MLV_SRC_DIR}/mlv/mcraw/RawData.cpp
        ${MLV_SRC_DIR}/mlv/mcraw/RawData_Legacy.cpp
        ${MLV_SRC_DIR}/mlv/mcraw/mcraw.c
)

include_directories(
        ${MLV_SRC_DIR}/
        ${MLV_SRC_DIR}/avir/
        ${MLV_SRC_DIR}/ca_correct/
        ${MLV_SRC_DIR}/debayer/
        ${MLV_SRC_DIR}/dng/
        ${MLV_SRC_DIR}/matrix/
        ${MLV_SRC_DIR}/mlv/
        ${MLV_SRC_DIR}/mlv_include.h/
        ${MLV_SRC_DIR}/processing/
        ${MLV_SRC_DIR}/librtprocess/src/include/
)

add_subdirectory(src/librtprocess)

# Create the main mlvcore shared library (.so/.dylib)
add_library(${CMAKE_PROJECT_NAME} SHARED
        ${CMAKE_SOURCE_DIR}/jni/mlv_processor_jni.cpp
)

# For AndroidBitmap_* functions
find_library(
        jnigraphics-lib
        jnigraphics
)

# Link all static libraries into the shared library
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE
        android
        log
        processing
        ca
        debayer
        dng
        matrix
        mlv
        rtprocess
        m
        ${jnigraphics-lib}
)

# Set the output name to be consistent
set_target_properties(${CMAKE_PROJECT_NAME} PROPERTIES OUTPUT_NAME "mlvcore")
