# CMake 4.x refuses compatibility with < 3.5, so keep this at a modern floor.
cmake_minimum_required(VERSION 3.22)

project(greeter CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Produces libgreeter.so, loaded by CppEngine via System.loadLibrary("greeter").
# Note the split: greeter.cpp is portable logic, greeter_jni.cpp is the bridge.
add_library(greeter SHARED
        greeter.cpp
        greeter_jni.cpp)

find_library(log-lib log)
target_link_libraries(greeter ${log-lib})

# IMPORTANT for the benchmark to mean anything.
#
# A debug build sets CMAKE_BUILD_TYPE=Debug, i.e. -O0. But `gomobile bind`
# ALWAYS compiles Go optimised — there is no debug variant of the .aar. So an
# out-of-the-box debug build compares optimised Go against unoptimised C++ and
# makes C++ look several times slower than it is.
#
# Forcing -O2 here puts both engines on the same footing in every build type.
target_compile_options(greeter PRIVATE -O2)
