from setuptools import setup, find_packages

with open("README.md", "r", encoding="utf-8") as f:
    long_description = f.read()

setup(
    name="distributed-cache-client",
    version="1.0.0",
    author="Cache Team",
    author_email="cache@example.com",
    description="Python client SDK for the distributed in-memory cache system",
    long_description=long_description,
    long_description_content_type="text/markdown",
    url="https://github.com/example/distributed-cache",
    packages=find_packages(exclude=["tests*"]),
    python_requires=">=3.10",
    classifiers=[
        "Development Status :: 4 - Beta",
        "Intended Audience :: Developers",
        "License :: OSI Approved :: MIT License",
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.10",
        "Programming Language :: Python :: 3.11",
        "Programming Language :: Python :: 3.12",
        "Topic :: Software Development :: Libraries",
        "Topic :: System :: Distributed Computing",
    ],
    extras_require={
        "dev": ["pytest>=7.0", "pytest-cov>=4.0"],
    },
)
