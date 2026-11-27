from setuptools import Extension, setup


setup(
    name="unipus-helper-native",
    ext_modules=[
        Extension(
            "unipus_helper._textmatch",
            ["unipus_helper/_textmatch.cpp"],
            language="c++",
        ),
    ],
)
