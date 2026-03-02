export default [
  {
    settings: {
      "import/resolver": {
        node: {
          paths: ["altitude"], // <-- makes "static/..." resolve under altitude/
          extensions: [".js"],
        },
      },
    },
  },
];