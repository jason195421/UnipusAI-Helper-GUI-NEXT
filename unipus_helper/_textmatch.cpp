#define PY_SSIZE_T_CLEAN
#include <Python.h>

#include <cctype>
#include <string>
#include <unordered_set>
#include <utility>
#include <vector>

namespace {

const std::unordered_set<std::string> kStopWords = {
    "the", "and", "that", "with", "from", "this", "have", "were", "their",
    "they", "into", "such", "when", "what", "which", "been", "also", "more",
    "some", "many", "other", "after", "before", "over", "than", "them",
};

bool read_strings(PyObject* sequence, std::vector<std::string>& output) {
    PyObject* fast = PySequence_Fast(sequence, "expected a sequence of strings");
    if (fast == nullptr) {
        return false;
    }
    const Py_ssize_t count = PySequence_Fast_GET_SIZE(fast);
    PyObject** items = PySequence_Fast_ITEMS(fast);
    output.reserve(static_cast<size_t>(count));
    for (Py_ssize_t i = 0; i < count; ++i) {
        if (!PyUnicode_Check(items[i])) {
            PyErr_SetString(PyExc_TypeError, "all items must be strings");
            Py_DECREF(fast);
            return false;
        }
        Py_ssize_t size = 0;
        const char* value = PyUnicode_AsUTF8AndSize(items[i], &size);
        if (value == nullptr) {
            Py_DECREF(fast);
            return false;
        }
        output.emplace_back(value, static_cast<size_t>(size));
    }
    Py_DECREF(fast);
    return true;
}

std::unordered_set<std::string> tokenize(const std::string& text) {
    std::unordered_set<std::string> words;
    size_t start = 0;
    while (start < text.size()) {
        while (start < text.size() && std::isspace(static_cast<unsigned char>(text[start]))) {
            ++start;
        }
        size_t end = start;
        while (end < text.size() && !std::isspace(static_cast<unsigned char>(text[end]))) {
            ++end;
        }
        if (end > start) {
            std::string word = text.substr(start, end - start);
            if (word.size() > 2 && kStopWords.find(word) == kStopWords.end()) {
                words.insert(std::move(word));
            }
        }
        start = end;
    }
    return words;
}

PyObject* best_sentence_positions(PyObject*, PyObject* args, PyObject* kwargs) {
    PyObject* sentence_object = nullptr;
    PyObject* option_object = nullptr;
    double minimum_score = 0.34;
    static const char* keywords[] = {"sentences", "option_texts", "minimum_score", nullptr};
    if (!PyArg_ParseTupleAndKeywords(args, kwargs, "OO|d:best_sentence_positions",
                                     const_cast<char**>(keywords), &sentence_object,
                                     &option_object, &minimum_score)) {
        return nullptr;
    }
    if (minimum_score < 0.0 || minimum_score > 1.0) {
        PyErr_SetString(PyExc_ValueError, "minimum_score must be between 0 and 1");
        return nullptr;
    }

    std::vector<std::string> sentence_texts;
    std::vector<std::string> option_texts;
    if (!read_strings(sentence_object, sentence_texts) ||
        !read_strings(option_object, option_texts)) {
        return nullptr;
    }
    if (sentence_texts.empty() || option_texts.empty()) {
        Py_RETURN_NONE;
    }

    std::vector<std::unordered_set<std::string>> sentence_words;
    sentence_words.reserve(sentence_texts.size());
    for (const auto& sentence : sentence_texts) {
        sentence_words.emplace_back(tokenize(sentence));
    }

    PyObject* result = PyList_New(static_cast<Py_ssize_t>(option_texts.size()));
    if (result == nullptr) {
        return nullptr;
    }
    for (size_t option_index = 0; option_index < option_texts.size(); ++option_index) {
        const auto words = tokenize(option_texts[option_index]);
        if (words.size() < 2) {
            Py_DECREF(result);
            Py_RETURN_NONE;
        }
        Py_ssize_t best_index = -1;
        double best_score = 0.0;
        for (size_t sentence_index = 0; sentence_index < sentence_words.size(); ++sentence_index) {
            size_t overlap = 0;
            for (const auto& word : words) {
                overlap += sentence_words[sentence_index].count(word);
            }
            const double score = static_cast<double>(overlap) / words.size();
            if (score > best_score) {
                best_index = static_cast<Py_ssize_t>(sentence_index);
                best_score = score;
            }
        }
        if (best_index < 0 || best_score < minimum_score) {
            Py_DECREF(result);
            Py_RETURN_NONE;
        }
        PyObject* index = PyLong_FromSsize_t(best_index);
        if (index == nullptr) {
            Py_DECREF(result);
            return nullptr;
        }
        PyList_SET_ITEM(result, static_cast<Py_ssize_t>(option_index), index);
    }
    return result;
}

PyMethodDef methods[] = {
    {"best_sentence_positions", reinterpret_cast<PyCFunction>(best_sentence_positions),
     METH_VARARGS | METH_KEYWORDS, "Find each option's highest-overlap sentence."},
    {nullptr, nullptr, 0, nullptr},
};

PyModuleDef module = {
    PyModuleDef_HEAD_INIT,
    "_textmatch",
    "C++ keyword matching accelerator.",
    -1,
    methods,
};

}  // namespace

PyMODINIT_FUNC PyInit__textmatch() {
    return PyModule_Create(&module);
}
